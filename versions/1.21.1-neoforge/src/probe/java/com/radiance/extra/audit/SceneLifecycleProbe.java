package com.radiance.extra.audit;

import com.radiance.api.render.RenderSpace;
import com.radiance.api.render.SectionScene;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import org.joml.*;

/** Destructive lifecycle checks run only after the owned copy's measurement windows have ended. */
public final class SceneLifecycleProbe {
    private static int step, expectedReady;
    private static long until, nextTrace;
    private static long nextStatus;
    private static Member target;
    private static BlockPos changed;
    private static CompletableFuture<?> operation;
    private static boolean polling;
    private static net.minecraft.client.multiplayer.ClientLevel previousLevel;
    private static final Path OUTPUT = Path.of(System.getProperty("validation.result"));

    private SceneLifecycleProbe() {}

    public static boolean poll(Minecraft mc, long now) throws Exception {
        if (polling) return false;
        polling = true;
        try {
            return pollOwned(mc, now);
        } finally {
            polling = false;
        }
    }

    private static boolean pollOwned(Minecraft mc, long now) throws Exception {
        if (now >= nextTrace) {
            nextTrace = now + 1_000_000_000L;
            String trace = ChunkProxy.drainUpdateTraceNative();
            if (!trace.isBlank())
                Files.writeString(
                        OUTPUT.resolve("chunk-trace.txt"),
                        trace + "\n",
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND);
        }
        if (now >= nextStatus) {
            nextStatus = now + 2_000_000_000L;
            Files.writeString(
                    OUTPUT.resolve("source-scene-lifecycle-state.jsonl"),
                    "{\"step\":"
                            + step
                            + ",\"java_ns\":"
                            + now
                            + ",\"expectedReady\":"
                            + expectedReady
                            + ",\"ready\":"
                            + (target == null ? -1 : ready(target))
                            + ",\"sourceSections\":"
                            + (target == null ? -1 : sectionCount(target))
                            + ",\"registeredMembers\":"
                            + (target == null ? -1 : target.sections.size())
                            + ",\"reloadDone\":"
                            + (operation != null && operation.isDone())
                            + ",\"overlay\":\""
                            + (mc.getOverlay() == null
                                    ? "none"
                                    : mc.getOverlay().getClass().getName())
                            + "\"}\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        if (step == 0) {
            target =
                    entries().stream()
                            .filter(e -> e.sections != null)
                            .max(Comparator.comparingInt(e -> e.sections.size()))
                            .orElseThrow();
            checkPoses();
            if (Boolean.getBoolean("radiance.audit.persistentSources130"))
                checkEntitySpaces(mc, target);
            expectedReady = ready(target);
            if (expectedReady == 0) throw new IllegalStateException("No published source sections");
            target.sections.members(List.of());
            if (ready(target) != 0)
                throw new IllegalStateException("Released sections still address their old slot");
            event("section-members-removed", now);
            step = 1;
            until = now + 500_000_000L;
        } else if (step == 1 && now >= until) {
            target.sections.members(
                    ((VanillaChunkedSubLevelRenderData) target.source.getRenderData())
                            .allRenderSections());
            step = 2;
            until = now + 90_000_000_000L;
        } else if (step == 2) {
            if (ready(target) < expectedReady) {
                if (now > until)
                    throw new IllegalStateException(
                            "Section resubmission did not publish all content");
                return false;
            }
            event("section-members-republished", now);
            changed = mc.player.blockPosition().offset(2, 2, 0);
            if (!mc.level.getBlockState(changed).isAir())
                throw new IllegalStateException(
                        "Interaction fixture must be air; do not overwrite a user's block");
            var server = mc.getSingleplayerServer();
            var dimension = mc.level.dimension();
            operation =
                    CompletableFuture.runAsync(
                            () ->
                                    server.getLevel(dimension)
                                            .setBlock(
                                                    changed,
                                                    Blocks.WHITE_CONCRETE.defaultBlockState(),
                                                    3),
                            server);
            step = 3;
            until = now + 30_000_000_000L;
        } else if (step == 3) {
            if (!operation.isDone() || !mc.level.getBlockState(changed).is(Blocks.WHITE_CONCRETE)) {
                if (now > until)
                    throw new IllegalStateException("Client did not receive placed block");
                return false;
            }
            operation.join();
            event("block-placement-client-state", now);
            step = 4;
            until = now + 3_000_000_000L;
        } else if (step == 4 && now >= until) {
            var server = mc.getSingleplayerServer();
            var dimension = mc.level.dimension();
            operation =
                    CompletableFuture.runAsync(
                            () ->
                                    server.getLevel(dimension)
                                            .setBlock(changed, Blocks.AIR.defaultBlockState(), 3),
                            server);
            step = 5;
            until = now + 30_000_000_000L;
        } else if (step == 5) {
            if (!operation.isDone() || !mc.level.getBlockState(changed).isAir()) {
                if (now > until)
                    throw new IllegalStateException("Client did not receive block removal");
                return false;
            }
            operation.join();
            event("block-removal-client-state", now);
            operation = mc.reloadResourcePacks();
            step = 6;
            until = now + 120_000_000_000L;
        } else if (step == 6) {
            if (!operation.isDone() || mc.getOverlay() != null || ready(target) < expectedReady) {
                if (now > until)
                    throw new IllegalStateException(
                            "Resource reload did not recover source content");
                return false;
            }
            operation.join();
            checkPoses();
            event("resource-reload", now);
            // disconnect pumps nested ticks: leave this state before calling it.
            step = 7;
            until = now + 120_000_000_000L;
            previousLevel = mc.level;
            target = null;
            mc.level.disconnect();
            mc.disconnect(new TitleScreen());
        } else if (step == 7
                && mc.level == null
                && mc.player == null
                && mc.screen instanceof TitleScreen
                && mc.getOverlay() == null) {
            step = 8;
            mc.createWorldOpenFlows()
                    .openWorld("Newport City", () -> mc.setScreen(new TitleScreen()));
        } else if (step == 8
                && mc.level != null
                && mc.level != previousLevel
                && mc.player != null) {
            target =
                    entries().stream()
                            .filter(e -> e.sections != null)
                            .max(Comparator.comparingInt(e -> e.sections.size()))
                            .orElse(null);
            if (target == null || ready(target) < expectedReady) {
                if (now > until)
                    throw new IllegalStateException("World re-entry did not restore source scene");
                return false;
            }
            checkPoses();
            mc.setScreen(new ChatScreen(""));
            event("world-reentry", now);
            Files.writeString(
                    OUTPUT.resolve("source-scene-lifecycle-pass.txt"),
                    "Membership removal/resubmission, source-space composition, placement/removal client state, reload and world re-entry passed. Native trace certifies publication separately.\n");
            step = 9;
        }
        return step == 9;
    }

    private static int ready(Member entry) {
        if (!(entry.source.getRenderData() instanceof VanillaChunkedSubLevelRenderData data))
            return 0;
        return (int) data.allRenderSections().stream().filter(ChunkProxy::isChunkReady).count();
    }

    private static int sectionCount(Member entry) {
        return entry.source.getRenderData() instanceof VanillaChunkedSubLevelRenderData data
                ? data.allRenderSections().size()
                : 0;
    }

    private record Member(
            Object state,
            ClientSubLevel source,
            RenderSpace space,
            Vector3d origin,
            SectionScene sections) {}

    private static Object field(Object target, String name) throws ReflectiveOperationException {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Collection<Member> entries() throws ReflectiveOperationException {
        var registry = SableSceneRegistry.class.getDeclaredField("ENTRIES");
        registry.setAccessible(true);
        Collection<?> entries = ((Map<?, ?>) registry.get(null)).values();
        var result = new ArrayList<Member>();
        for (Object entry : entries)
            result.add(
                    new Member(
                            entry,
                            (ClientSubLevel) field(entry, "source"),
                            (RenderSpace) field(entry, "space"),
                            (Vector3d) field(entry, "origin"),
                            (SectionScene) field(entry, "sections")));
        return result;
    }

    private static Object component(Object pose, String name) throws ReflectiveOperationException {
        var method = pose.getClass().getDeclaredMethod(name);
        method.setAccessible(true);
        return method.invoke(pose);
    }

    private static void checkPoses() throws ReflectiveOperationException {
        for (var entry : entries()) {
            Object pose = field(entry.state, "pose");
            if (pose == null) continue;
            var world = entry.space.transform(new Matrix4d());
            for (var local : List.of(new Vector3d(.125, .25, -.375), new Vector3d(64, -32, 128))) {
                var source =
                        new Vector3d(local)
                                .add(entry.origin)
                                .sub((Vector3dc) component(pose, "rotationPoint"))
                                .mul((Vector3dc) component(pose, "scale"));
                ((Quaterniondc) component(pose, "orientation")).transform(source);
                source.add((Vector3dc) component(pose, "position"));
                var actual = world.transformPosition(new Vector3d(local));
                if (source.distance(actual) > 1e-7)
                    throw new IllegalStateException(
                            "Spatial composition differs from Sable Pose3dc: "
                                    + source
                                    + " / "
                                    + actual);
            }
        }
    }

    static void checkEntitySpaces(Minecraft mc) throws Exception {
        var member = entries().stream().findFirst().orElseThrow();
        checkEntitySpaces(mc, member);
    }

    private static void checkEntitySpaces(Minecraft mc, Member member) throws Exception {
        var cow = net.minecraft.world.entity.EntityType.COW.create(mc.level);
        var vehicle = net.minecraft.world.entity.EntityType.BOAT.create(mc.level);
        var movement =
                (dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision
                                .EntityMovementExtension)
                        cow;
        var local = new Vector3d(member.origin).add(1.25, 2.5, 3.75);
        var update = member.state.getClass().getDeclaredMethod("update", float.class);
        update.setAccessible(true);
        cow.setPos(local.x, local.y, local.z);
        cow.xOld = local.x - .1;
        cow.yOld = local.y;
        cow.zOld = local.z + .2;
        if (dev.ryanhcode.sable.Sable.HELPER.getContaining(cow) != member.source)
            throw new AssertionError("Contained fixture is outside the actual plot");
        for (float partial : new float[] {0, .37F, .91F}) {
            update.invoke(member.state, partial);
            var position = new Vector3d(cow.xOld, cow.yOld, cow.zOld).lerp(local, partial);
            var transform =
                    com.radiance.extra.compatibility.sable.SableSubLevelBridge
                            .entityRenderTransform(
                                    cow, partial, position.x, position.y, position.z);
            Object pose = field(member.state, "pose");
            var expected =
                    new Vector3d(position)
                            .sub((Vector3dc) component(pose, "rotationPoint"))
                            .mul((Vector3dc) component(pose, "scale"));
            ((Quaterniondc) component(pose, "orientation")).transform(expected);
            expected.add((Vector3dc) component(pose, "position"));
            if (transform == null
                    || !transform.rotatesModel()
                    || transform.placement() == null
                    || expected.distance(transform.position()) > 1e-7)
                throw new AssertionError("Contained pose differs");
            var provider = new com.radiance.client.vertex.StorageVertexConsumerProvider(256);
            try {
                provider.declaredParts(cow.getId(), "sable-contained-regression");
                provider.sourceOwner = cow;
                provider.modelPlacement = transform.placement();
                var stack = new com.mojang.blaze3d.vertex.PoseStack();
                stack.mulPose(transform.orientation());
                if (!com.radiance.adapter.vanilla.VanillaLivingReplacement.eligible(
                        mc.getEntityRenderDispatcher(), cow, provider, provider))
                    throw new AssertionError("Contained vanilla owner fell back");
                com.radiance.adapter.vanilla.VanillaLivingReplacement.render(
                        mc.getEntityRenderDispatcher(), cow, partial, stack, provider, 15728640);
                com.radiance.adapter.vanilla.VanillaEntityAttachments.collect(
                        mc.getEntityRenderDispatcher(), cow, partial, stack, provider);
                if (provider.takeRigidModels().isEmpty())
                    throw new AssertionError("Contained source did not produce local instances");
            } finally {
                provider.close();
            }
        }
        var renderer = mc.getEntityRenderDispatcher().getRenderer(cow);
        var originalName =
                java.util.Arrays.stream(
                                net.minecraft.client.renderer.entity.EntityRenderer.class
                                        .getDeclaredMethods())
                        .filter(
                                m ->
                                        m.getName().contains("sable")
                                                && m.getName().contains("renderNameTag")
                                                && m.getParameterCount() == 2)
                        .findFirst()
                        .orElseThrow();
        originalName.setAccessible(true);
        var expectedName =
                (org.joml.Quaternionf)
                        originalName.invoke(renderer, mc.getEntityRenderDispatcher(), cow);
        var currentName =
                com.radiance.api.render.SourceHooks.nameOrientation(
                        cow, .37F, mc.getEntityRenderDispatcher().cameraOrientation());
        if (!new org.joml.Matrix3f()
                .set(expectedName)
                .equals(new org.joml.Matrix3f().set(currentName), 1e-6F))
            throw new AssertionError("Source name orientation differs");
        var bed = net.minecraft.core.BlockPos.containing(local.x, local.y, local.z);
        var savedBed = mc.level.getBlockState(bed);
        try {
            mc.level.setBlock(
                    bed, net.minecraft.world.level.block.Blocks.RED_BED.defaultBlockState(), 0);
            cow.setSleepingPos(bed);
            cow.setPose(net.minecraft.world.entity.Pose.SLEEPING);
            if (cow.getBedOrientation() == null)
                throw new AssertionError("Sleeping fixture did not reach the bed source");
            var expectedPose = new com.mojang.blaze3d.vertex.PoseStack();
            var actualPose = new com.mojang.blaze3d.vertex.PoseStack();
            var originalSleep =
                    java.util.Arrays.stream(
                                    net.minecraft.client.renderer.entity.LivingEntityRenderer.class
                                            .getDeclaredMethods())
                            .filter(
                                    m ->
                                            m.getName().contains("sable")
                                                    && m.getName().contains("setupRotations")
                                                    && m.getParameterCount() == 7)
                            .findFirst()
                            .orElseThrow();
            originalSleep.setAccessible(true);
            originalSleep.invoke(
                    renderer,
                    cow,
                    0F,
                    .37F,
                    expectedPose,
                    null,
                    0,
                    new org.spongepowered.asm.mixin.injection.callback.CallbackInfo(
                            "sleep-oracle", false));
            com.radiance.api.render.SourceHooks.sleepingPose(cow, actualPose);
            if (!expectedPose.last().pose().equals(actualPose.last().pose(), 1e-6F))
                throw new AssertionError("Source sleeping pose differs");
        } finally {
            cow.clearSleepingPos();
            cow.setPose(net.minecraft.world.entity.Pose.STANDING);
            mc.level.setBlock(bed, savedBed, 0);
        }
        cow.setPos(mc.player.getX() + 2, mc.player.getY(), mc.player.getZ());
        cow.xOld = cow.getX() - .3;
        cow.yOld = cow.getY();
        cow.zOld = cow.getZ();
        if (dev.ryanhcode.sable.Sable.HELPER.getContaining(cow) != null)
            throw new AssertionError("Tracking fixture must be outside the plot");
        movement.sable$setTrackingSubLevel(member.source);
        for (float partial : new float[] {0, .37F, .91F}) {
            update.invoke(member.state, partial);
            var old =
                    member.source
                            .lastPose()
                            .transformPositionInverse(new Vector3d(cow.xOld, cow.yOld, cow.zOld));
            var current =
                    member.source
                            .logicalPose()
                            .transformPositionInverse(
                                    new Vector3d(cow.getX(), cow.getY(), cow.getZ()));
            var expected =
                    member.source.renderPose(partial).transformPosition(old.lerp(current, partial));
            var actual =
                    com.radiance.extra.compatibility.sable.SableSubLevelBridge
                            .entityRenderTransform(cow, partial, 0, 0, 0);
            if (actual == null
                    || actual.rotatesModel()
                    || actual.placement() != null
                    || actual.position().distance(expected) > 1e-6)
                throw new AssertionError("Tracking interpolation differs from Sable");
        }
        vehicle.setPos(cow.position());
        cow.startRiding(vehicle, true);
        if (!cow.isPassenger()
                || com.radiance.extra.compatibility.sable.SableSubLevelBridge.entityRenderTransform(
                                cow, .37F, 0, 0, 0)
                        != null)
            throw new AssertionError("Passenger received tracking transform twice");
        cow.stopRiding();
        movement.sable$setTrackingSubLevel(null);
        if (com.radiance.extra.compatibility.sable.SableSubLevelBridge.entityRenderTransform(
                        cow, .37F, 0, 0, 0)
                != null) throw new AssertionError("Exited source retained tracking space");
        cow.setPos(local.x, local.y, local.z);
        if (com.radiance.extra.compatibility.sable.SableSubLevelBridge.entityRenderTransform(
                        cow, .37F, local.x, local.y, local.z)
                == null) throw new AssertionError("Reentered plot lost source");
        cow.discard();
        vehicle.discard();
        update.invoke(member.state, mc.getTimer().getGameTimeDeltaPartialTick(true));
        Files.writeString(
                OUTPUT.resolve("entity-space-pass.txt"),
                "Actual Sable plot membership, contained local-instance entry, tracking interpolation, passenger exemption, exit and reentry passed; user dynamic visual pending.\n");
    }

    private static void event(String name, long now) throws java.io.IOException {
        Files.writeString(
                OUTPUT.resolve("source-scene-lifecycle.jsonl"),
                "{\"event\":\"" + name + "\",\"java_ns\":" + now + "}\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
    }
}
