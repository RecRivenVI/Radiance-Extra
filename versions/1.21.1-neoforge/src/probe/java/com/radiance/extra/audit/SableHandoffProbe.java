package com.radiance.extra.audit;

import com.radiance.api.render.*;
import com.radiance.client.proxy.world.ChunkProxy;
import com.radiance.extra.compatibility.flywheel.FlywheelHandoffs;
import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.*;
import dev.ryanhcode.sable.sublevel.render.vanilla.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;

/** Actual source operations in an owned world, without performance windows or desktop input. */
final class SableHandoffProbe {
    private static int step, frames;
    private static boolean done;
    private static long started, stamp;
    private static long[] stable;
    private static long membershipRefreshStarted = -1;
    private static long[] visualBaseline;
    private static ServerSubLevel visualA, visualB;
    private static BlockPos mergeAnchor, mergeGoal, sourceGlue;
    private static boolean watchVisuals;
    private static boolean adapterCycle, watchBer;
    private static SectionHandoff unloadingScope;
    private static java.util.UUID unloadingSource;
    private static RenderSpace unloadingSpace;
    private static final java.util.concurrent.atomic.AtomicBoolean unloadPrepared =
            new java.util.concurrent.atomic.AtomicBoolean();
    private static final int[] unloadCallbacks = new int[2];
    private static SectionHandoff staleScope;
    private static long staleEpoch;
    private static int savedDistance;
    private static String berClass;
    private static long[] catnipStable, flywheelStable;
    private static long minimumBer = Long.MAX_VALUE;
    private static long minimumVisuals = Long.MAX_VALUE;
    private static final List<ServerSubLevel> serverSources = new ArrayList<>();
    private static CompletableFuture<Void> operation;
    private static final Map<String, Object> result = new LinkedHashMap<>();

    static void poll(Minecraft mc) {
        if (!"handoff".equals(System.getProperty("radiance.audit.worldMaterials134"))
                || done
                || mc.level == null
                || mc.player == null
                || mc.getOverlay() != null) return;
        try {
            run(mc);
        } catch (Throwable error) {
            result.put("error", error.toString());
            com.mojang.logging.LogUtils.getLogger().error("Sable handoff check failed", error);
            finish(mc, false);
        }
    }

    private static void run(Minecraft mc) throws Exception {
        long now = System.nanoTime();
        if (started == 0) started = now;
        if (now - started > 240_000_000_000L)
            throw new AssertionError("Source handoff deadline exceeded at step " + step);
        if (operation != null) {
            if (!operation.isDone()) return;
            operation.join();
            operation = null;
        }
        var container = SubLevelContainer.getContainer(mc.level);
        if (container == null) return;
        long[] visuals = FlywheelHandoffs.counters();
        if (watchBer) {
            long available =
                    BlockEntityPublications.census().getOrDefault(berClass, new long[3])[0];
            minimumBer = Math.min(minimumBer, available);
            if (available < 1)
                throw new AssertionError("Ordinary BER publication gap at step " + step);
        }
        if (watchVisuals) {
            minimumVisuals = Math.min(minimumVisuals, visuals[7]);
            if (visuals[7] < 2)
                throw new AssertionError(
                        "Flywheel publication gap at step "
                                + step
                                + ": "
                                + Arrays.toString(visuals));
        }
        if (now - stamp > 100_000_000L) {
            stamp = now;
            Files.writeString(
                    output().resolve("handoff-trace.jsonl"),
                    new com.google.gson.Gson()
                                    .toJson(
                                            Map.of(
                                                    "step",
                                                    step,
                                                    "sources",
                                                    container.getAllSubLevels().size(),
                                                    "handoff",
                                                    SableSceneRegistry.handoffCounters(),
                                                    "models",
                                                    SableSceneRegistry.singleModelCounters(),
                                                    "flywheel",
                                                    visuals,
                                                    "mergeLinks",
                                                    SableSceneRegistry.mergeLinks(),
                                                    "merged",
                                                    SableSceneRegistry.mergedTransfers(),
                                                    "unmapped",
                                                    SableSceneRegistry.unmappedTransfers(),
                                                    "blockEntities",
                                                    BlockEntityPublications.census()))
                            + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        if (step == 0) {
            var sourceAudit = new LinkedHashMap<String, Object>();
            var field =
                    com.radiance.mixin_related.SourceCompatibility.Source.class.getDeclaredField(
                            "classes");
            field.setAccessible(true);
            var trail =
                    cpw.mods.modlauncher.Launcher.INSTANCE
                            .environment()
                            .getProperty(
                                    cpw.mods.modlauncher.api.IEnvironment.Keys.AUDITTRAIL.get())
                            .orElseThrow();
            for (String name :
                    (List<String>)
                            field.get(
                                    com.radiance.mixin_related.SourceCompatibility.Source.BAKED)) {
                Class.forName(
                        name,
                        false,
                        com.radiance.mixin_related.SourceCompatibility.class.getClassLoader());
                sourceAudit.put(
                        name,
                        trail.getActivityFor(name).stream()
                                .map(
                                        cpw.mods.modlauncher.api.ITransformerActivity
                                                ::getActivityString)
                                .toList());
            }
            Files.writeString(
                    output().resolve("baked-source-audit.json"),
                    new com.google.gson.GsonBuilder()
                                    .setPrettyPrinting()
                                    .create()
                                    .toJson(sourceAudit)
                            + "\n");
            operate(
                    mc,
                    () -> {
                        var server = mc.getSingleplayerServer();
                        var world = server.overworld();
                        for (String command :
                                List.of(
                                        "gamerule doMobSpawning false",
                                        "kill @e[type=!minecraft:player]",
                                        "gamemode spectator @a",
                                        "time set noon",
                                        "fill -5 70 -5 20 70 5 stone",
                                        "fill -5 71 -5 20 80 5 air",
                                        "tp @a 3 75 9 facing 3 71 0"))
                            server.getCommands()
                                    .performPrefixedCommand(
                                            server.createCommandSourceStack()
                                                    .withSuppressedOutput()
                                                    .withPermission(4),
                                            command);
                        for (int x : new int[] {0, 6}) {
                            var p = new BlockPos(x, 71, 0);
                            world.setBlock(p, Blocks.GOLD_BLOCK.defaultBlockState(), 3);
                            serverSources.add(
                                    SubLevelAssemblyHelper.assembleBlocks(
                                            world,
                                            p,
                                            List.of(p),
                                            new BoundingBox3i(x, 71, 0, x, 71, 0)));
                        }
                    });
            step = 1;
            return;
        }
        if (step == 1) {
            if (container.getAllSubLevels().size() != 2 || !settled() || ++frames < 90) return;
            if ("1"
                    .equals(
                            com.radiance.audit.ExperimentAccess.getenv(
                                    "RADIANCE_RECEIVED_LIGHT_CHECK"))) {
                result.put(
                        "receivedLighting",
                        ReceivedLightingProbe.sample(mc, container.getAllSubLevels().getFirst()));
                finish(mc, true);
                return;
            }
            stable = SableSceneRegistry.singleModelCounters();
            if (stable[1] < 1 || stable[2] != 0)
                throw new AssertionError(
                        "Stable single-block sources did not share a baked resource: "
                                + Arrays.toString(stable));
            screenshot(mc, "single-shared.png");
            step = 2;
            frames = 0;
            return;
        }
        if (step == 2) {
            if (++frames < 90) return;
            var after = SableSceneRegistry.singleModelCounters();
            if (after[0] != stable[0] || after[3] <= stable[3])
                throw new AssertionError("Unchanged single models were rebuilt or not selected");
            result.put("stableWindowBefore", stable);
            result.put("stableWindowAfter", after);
            operate(mc, () -> block(mc, 1, Blocks.STONE.defaultBlockState()));
            step = 3;
            return;
        }
        if (step == 3) {
            var client = container.getSubLevel(serverSources.getFirst().getUniqueId());
            if (!(client instanceof ClientSubLevel c)
                    || !(c.getRenderData() instanceof VanillaChunkedSubLevelRenderData)
                    || !settled()) return;
            screenshot(mc, "single-to-multi.png");
            operate(mc, () -> block(mc, 1, Blocks.AIR.defaultBlockState()));
            step = 4;
            return;
        }
        if (step == 4) {
            var client = container.getSubLevel(serverSources.getFirst().getUniqueId());
            if (!(client instanceof ClientSubLevel c)
                    || !(c.getRenderData() instanceof VanillaSingleSubLevelRenderData)
                    || !settled()) return;
            screenshot(mc, "multi-to-single.png");
            operate(
                    mc,
                    () -> {
                        block(mc, 0, Blocks.STONE.defaultBlockState());
                        block(mc, 1, Blocks.STONE.defaultBlockState());
                        block(mc, 2, Blocks.STONE.defaultBlockState());
                    });
            step = 5;
            return;
        }
        if (step == 5) {
            var client = container.getSubLevel(serverSources.getFirst().getUniqueId());
            if (!(client instanceof ClientSubLevel c)
                    || !(c.getRenderData() instanceof VanillaChunkedSubLevelRenderData)
                    || !settled()) return;
            if (membershipRefreshStarted < 0) {
                membershipRefreshStarted = SableSceneRegistry.handoffCounters()[0];
                SableSceneRegistry.sectionsChanged(c);
                frames = 0;
                return;
            }
            if (++frames < 8) return;
            if (SableSceneRegistry.handoffCounters()[0] != membershipRefreshStarted)
                throw new AssertionError(
                        "Ordinary section membership refresh created a whole-source handoff");
            result.put("ordinaryMembershipStreams", true);
            checkOverlappingScopes();
            operate(
                    mc,
                    () -> {
                        var parent = serverSources.getFirst();
                        var bounds = parent.getPlot().getBoundingBox();
                        var p = new BlockPos(bounds.minX() + 2, bounds.minY(), bounds.minZ());
                        serverSources.add(
                                SubLevelAssemblyHelper.assembleBlocks(
                                        mc.getSingleplayerServer().overworld(),
                                        p,
                                        List.of(p),
                                        new BoundingBox3i(
                                                p.getX(), p.getY(), p.getZ(), p.getX(), p.getY(),
                                                p.getZ())));
                    });
            step = 6;
            return;
        }
        if (step == 6) {
            if (container.getAllSubLevels().size() != 3 || !settled()) return;
            screenshot(mc, "split.png");
            operate(
                    mc,
                    () -> {
                        var child = serverSources.getLast();
                        var b = child.getPlot().getBoundingBox();
                        var p = new BlockPos(b.minX(), b.minY(), b.minZ());
                        var point =
                                child.logicalPose()
                                        .transformPosition(
                                                new org.joml.Vector3d(
                                                        p.getX(), p.getY(), p.getZ()));
                        var goal =
                                new BlockPos(
                                        (int) Math.round(point.x),
                                        (int) Math.round(point.y),
                                        (int) Math.round(point.z));
                        dev.simulated_team.simulated.util.SimAssemblyHelper.disassembleSubLevel(
                                mc.getSingleplayerServer().overworld(),
                                child,
                                p,
                                goal,
                                Rotation.NONE,
                                false);
                    });
            step = 7;
            return;
        }
        if (step == 7) {
            if (container.getAllSubLevels().size() != 2 || !settled()) return;
            screenshot(mc, "returned-to-world.png");
            operation = mc.reloadResourcePacks();
            step = 8;
            frames = 0;
            return;
        }
        if (step == 8) {
            if (!settled() || ++frames < 60) return;
            if (SableSceneRegistry.singleModelCounters()[2] != 0)
                throw new AssertionError(
                        "A reviewed vanilla composite model used the dynamic fallback");
            screenshot(mc, "reloaded.png");
            result.put("handoffs", SableSceneRegistry.handoffCounters());
            result.put("models", SableSceneRegistry.singleModelCounters());
            result.put("sourceCount", container.getAllSubLevels().size());
            result.put(
                    "modelKinds",
                    container.getAllSubLevels().stream()
                            .map(
                                    s -> {
                                        var b = s.getPlot().getBoundingBox();
                                        var state =
                                                mc.level.getBlockState(
                                                        new BlockPos(b.minX(), b.minY(), b.minZ()));
                                        return mc.getBlockRenderer()
                                                .getBlockModel(state)
                                                .getClass()
                                                .getName();
                                    })
                            .toList());
            operate(
                    mc,
                    () -> {
                        var world = mc.getSingleplayerServer().overworld();
                        for (int x : new int[] {2, 10})
                            world.setBlock(
                                    new BlockPos(x, 71, 3),
                                    com.simibubi.create.AllBlocks.SHAFT
                                            .getDefaultState()
                                            .setValue(
                                                    RotatedPillarBlock.AXIS,
                                                    net.minecraft.core.Direction.Axis.X),
                                    3);
                    });
            step = 9;
            frames = 0;
            return;
        }
        if (step == 9) {
            if (++frames < 60 || visuals[7] < 2) return;
            visualBaseline = visuals;
            watchVisuals = true;
            operate(
                    mc,
                    () -> {
                        visualA = assemble(mc, new BlockPos(2, 71, 3));
                        visualB = assemble(mc, new BlockPos(10, 71, 3));
                    });
            step = 10;
            return;
        }
        if (step == 10) {
            if (container.getAllSubLevels().size() != 4 || !settled()) return;
            screenshot(mc, "flywheel-assembled.png");
            operate(mc, () -> returnToWorld(mc, visualA));
            step = 11;
            return;
        }
        if (step == 11) {
            if (container.getAllSubLevels().size() != 3 || !settled()) return;
            screenshot(mc, "flywheel-world-return.png");
            operate(
                    mc,
                    () -> {
                        var world = mc.getSingleplayerServer().overworld();
                        var parent = serverSources.getFirst();
                        mergeAnchor = visualB.getPlot().getCenterBlock();
                        mergeGoal = parent.getPlot().getCenterBlock().above(2);
                        sourceGlue = mergeAnchor.west();
                        var position =
                                parent.logicalPose()
                                        .transformPosition(
                                                new org.joml.Vector3d(
                                                        mergeGoal.getX() + .5,
                                                        mergeGoal.getY() + .5,
                                                        mergeGoal.getZ() + .5));
                        visualB.logicalPose()
                                .rotationPoint()
                                .set(
                                        mergeAnchor.getX() + .5,
                                        mergeAnchor.getY() + .5,
                                        mergeAnchor.getZ() + .5);
                        visualB.logicalPose().position().set(position);
                        visualB.logicalPose().orientation().set(parent.logicalPose().orientation());
                        SubLevelContainer.getContainer(world)
                                .physicsSystem()
                                .getPipeline()
                                .teleport(visualB, position, visualB.logicalPose().orientation());
                        world.setBlock(
                                sourceGlue,
                                dev.simulated_team.simulated.index.SimBlocks.MERGING_GLUE
                                        .getDefaultState()
                                        .setValue(
                                                net.minecraft.world.level.block.state.properties
                                                        .BlockStateProperties.FACING,
                                                net.minecraft.core.Direction.WEST),
                                3);
                        world.setBlock(
                                mergeGoal,
                                dev.simulated_team.simulated.index.SimBlocks.MERGING_GLUE
                                        .getDefaultState()
                                        .setValue(
                                                net.minecraft.world.level.block.state.properties
                                                        .BlockStateProperties.FACING,
                                                net.minecraft.core.Direction.EAST),
                                3);
                        var glue =
                                (dev.simulated_team.simulated.content.blocks.merging_glue
                                                .MergingGlueBlockEntity)
                                        world.getBlockEntity(sourceGlue);
                        glue.setPartnerPos(mergeGoal);
                        glue.sendData();
                        var partner =
                                (dev.simulated_team.simulated.content.blocks.merging_glue
                                                .MergingGlueBlockEntity)
                                        world.getBlockEntity(mergeGoal);
                        partner.setPartnerPos(sourceGlue);
                        partner.sendData();
                    });
            step = 12;
            frames = 0;
            return;
        }
        if (step == 12) {
            if (!settled() || SableSceneRegistry.mergeLinks() < 1 || ++frames < 30) return;
            operate(
                    mc,
                    () -> {
                        var world = mc.getSingleplayerServer().overworld();
                        world.destroyBlock(sourceGlue, false);
                        world.destroyBlock(mergeGoal, false);
                        dev.simulated_team.simulated.util.SimAssemblyHelper.disassembleSubLevel(
                                world, visualB, mergeAnchor, mergeGoal, Rotation.NONE, false);
                    });
            step = 13;
            return;
        }
        if (step == 13) {
            if (container.getAllSubLevels().size() == 2
                    && SableSceneRegistry.transferEvents().stream()
                            .anyMatch(
                                    event ->
                                            visualB.getUniqueId()
                                                    .toString()
                                                    .equals(event.get("source")))
                    && SableSceneRegistry.mergedTransfers() < 1)
                throw new AssertionError(
                        "Actual merge receiver was rejected: "
                                + SableSceneRegistry.transferEvents());
            if (container.getAllSubLevels().size() != 2 || !settled()) return;
            if (SableSceneRegistry.mergedTransfers() < 1)
                throw new AssertionError("Merge did not select its actual sublevel receiver");
            screenshot(mc, "flywheel-merged.png");
            operate(mc, () -> visualB = assemble(mc, mergeGoal));
            step = 14;
            return;
        }
        if (step == 14) {
            if (container.getAllSubLevels().size() != 3 || !settled()) return;
            screenshot(mc, "flywheel-split.png");
            operate(mc, () -> returnToWorld(mc, visualB));
            step = 15;
            return;
        }
        if (step == 15) {
            if (container.getAllSubLevels().size() != 2 || !settled()) return;
            if (adapterCycle) {
                result.put("minimumConsumableBerPublications", minimumBer);
                result.put("ordinaryBerTransfers", BlockEntityPublications.counters());
                watchBer = false;
                operation = mc.reloadResourcePacks();
                frames = 0;
                step = 22;
                return;
            }
            if (visuals[4] <= visualBaseline[4] || visuals[5] <= visualBaseline[5])
                throw new AssertionError(
                        "Real Flywheel instances never traversed both publication holds");
            result.put("flywheelBefore", visualBaseline);
            result.put("flywheelAfter", visuals);
            result.put("minimumPublishedFlywheelInstances", minimumVisuals);
            result.put("mergedTransfers", SableSceneRegistry.mergedTransfers());
            result.put("unmappedTransfers", SableSceneRegistry.unmappedTransfers());
            result.put("finalHandoffs", SableSceneRegistry.handoffCounters());
            watchVisuals = false;
            operation = mc.reloadResourcePacks();
            step = 16;
            frames = 0;
            return;
        }
        if (step == 16) {
            if (!settled() || ++frames < 60 || visuals[7] < 2) return;
            if (visuals[2] != 0) throw new AssertionError("Visual publication leaked after F3+T");
            result.put("flywheelAfterReload", visuals);
            screenshot(mc, "flywheel-reloaded.png");
            operate(mc, () -> visualB = assemble(mc, new BlockPos(2, 71, 3)));
            step = 17;
            return;
        }
        if (step == 17) {
            if (container.getAllSubLevels().size() != 3 || !settled() || visuals[7] != 2) return;
            watchVisuals = true;
            operate(
                    mc,
                    () -> {
                        var previous = visualB;
                        visualB = assemble(mc, previous.getPlot().getCenterBlock());
                        if (!previous.isRemoved())
                            SubLevelContainer.getContainer(mc.getSingleplayerServer().overworld())
                                    .removeSubLevel(
                                            previous,
                                            dev.ryanhcode.sable.sublevel.storage
                                                    .SubLevelRemovalReason.REMOVED);
                    });
            step = 18;
            return;
        }
        if (step == 18) {
            if (container.getAllSubLevels().size() != 3
                    || !settled()
                    || container.getSubLevel(visualB.getUniqueId()) == null) return;
            if (visuals[7] != 2)
                throw new AssertionError("Whole-parent split lost a shaft publication");
            result.put("emptiedParentSplit", true);
            watchVisuals = false;
            operate(
                    mc,
                    () ->
                            SubLevelContainer.getContainer(mc.getSingleplayerServer().overworld())
                                    .removeSubLevel(
                                            visualB,
                                            dev.ryanhcode.sable.sublevel.storage
                                                    .SubLevelRemovalReason.REMOVED));
            step = 19;
            return;
        }
        if (step == 19) {
            if (container.getAllSubLevels().size() != 2 || !settled()) return;
            if (visuals[7] != 1 || visuals[2] != 0)
                throw new AssertionError(
                        "Deleted source left a held Flywheel ghost: " + Arrays.toString(visuals));
            result.put("flywheelAfterSourceDeletion", visuals);
            result.put("cleanupHandoffs", SableSceneRegistry.handoffCounters());
            if (SableSceneRegistry.mergeLinks() != 0)
                throw new AssertionError("Merge relationship retained a removed receiver");
            operate(
                    mc,
                    () -> {
                        var world = mc.getSingleplayerServer().overworld();
                        world.setBlock(
                                new BlockPos(2, 71, 3),
                                dev.simulated_team.simulated.index.SimBlocks.PHYSICS_ASSEMBLER
                                        .getDefaultState(),
                                3);
                        world.setBlock(
                                new BlockPos(16, 71, 3),
                                com.simibubi.create.AllBlocks.CUCKOO_CLOCK.getDefaultState(),
                                3);
                        world.setBlock(
                                new BlockPos(18, 71, 3), Blocks.OAK_SIGN.defaultBlockState(), 3);
                        var sign =
                                (net.minecraft.world.level.block.entity.SignBlockEntity)
                                        world.getBlockEntity(new BlockPos(18, 71, 3));
                        sign.setText(
                                sign.getFrontText()
                                        .setMessage(
                                                0,
                                                net.minecraft.network.chat.Component.literal(
                                                        "BER handoff")),
                                true);
                        world.sendBlockUpdated(
                                sign.getBlockPos(), sign.getBlockState(), sign.getBlockState(), 3);
                    });
            step = 20;
            frames = 0;
            return;
        }
        if (step == 20) {
            if (++frames < 60 || !settled()) return;
            var entity = mc.level.getBlockEntity(new BlockPos(2, 71, 3));
            if (entity == null) return;
            berClass = entity.getClass().getName();
            if (BlockEntityPublications.census().getOrDefault(berClass, new long[3])[0] < 1) return;
            catnipStable =
                    com.radiance.extra.compatibility.catnip.CatnipTemplateGeometry.workCounters();
            flywheelStable =
                    com.radiance.extra.compatibility.flywheel.RadianceFlywheelBackend
                            .workCounters();
            frames = 0;
            step = 21;
            return;
        }
        if (step == 21) {
            if (++frames < 90) return;
            var after =
                    com.radiance.extra.compatibility.catnip.CatnipTemplateGeometry.workCounters();
            var fw =
                    com.radiance.extra.compatibility.flywheel.RadianceFlywheelBackend
                            .workCounters();
            if (after[1] <= catnipStable[1] || after[2] != catnipStable[2])
                throw new AssertionError(
                        "Stable Catnip templates not reused: "
                                + Arrays.toString(catnipStable)
                                + " -> "
                                + Arrays.toString(after));
            if (fw[0] != flywheelStable[0] || fw[1] != flywheelStable[1])
                throw new AssertionError("Stable Flywheel models were produced/uploaded again");
            result.put("stableCatnipBefore", catnipStable);
            result.put("stableCatnipAfter", after);
            result.put("stableFlywheelBefore", flywheelStable);
            result.put("stableFlywheelAfter", fw);
            result.put(
                    "backend",
                    dev.engine_room.flywheel.api.backend.Backend.REGISTRY
                            .getId(dev.engine_room.flywheel.impl.FlwConfig.INSTANCE.backend())
                            .toString());
            if (!dev.engine_room.flywheel.impl.FlwConfig.INSTANCE
                    .backend()
                    .equals(
                            dev.engine_room.flywheel.api.backend.Backend.REGISTRY.get(
                                    com.radiance.extra.compatibility.flywheel
                                            .RadianceFlywheelBackend.ID)))
                throw new AssertionError("Configured backend does not match the active mode");
            adapterCycle = true;
            watchBer = true;
            operate(mc, () -> visualB = assemble(mc, new BlockPos(2, 71, 3)));
            step = 11;
            return;
        }
        if (step == 22) {
            if (++frames < 60 || !settled()) return;
            if (BlockEntityPublications.counters()[1] != 0)
                throw new AssertionError("Ordinary BER transfer survived resource reload");
            if (BlockEntityPublications.census().getOrDefault(berClass, new long[3])[0] < 1)
                throw new AssertionError("Ordinary BER did not resume after F3+T");
            result.put("ordinaryBerAfterReload", BlockEntityPublications.census());
            screenshot(mc, "ordinary-ber-reloaded.png");
            beginHeldUnload(mc);
            frames = 0;
            step = 23;
            return;
        }
        if (step == 23) {
            if (container.getSubLevel(unloadingSource) != null) return;
            if (++frames < 6) return;
            try {
                unloadingSpace.version();
                throw new AssertionError("Held-unload check retained a live source space");
            } catch (IllegalStateException closed) {
                result.put("unloadedSpaceClosed", true);
            }
            if (unloadingScope.tryCommit())
                throw new AssertionError("Source unload published an unprepared external scope");
            unloadPrepared.set(true);
            if (!unloadingScope.tryCommit())
                throw new AssertionError("Source unload left a permanent section dependency");
            unloadingScope.close();
            if (unloadCallbacks[0] != 1 || unloadCallbacks[1] != 0)
                throw new AssertionError("Unloaded section scope did not complete once");
            result.put("sourceUnloadedWhileHeld", true);
            staleScope = new SectionHandoff();
            staleEpoch = ChunkProxy.sectionEpoch();
            savedDistance = mc.options.renderDistance().get();
            mc.options.renderDistance().set(savedDistance == 8 ? 9 : 8);
            mc.levelRenderer.allChanged();
            step = 24;
            return;
        }
        if (step == 24) {
            if (ChunkProxy.sectionEpoch() == staleEpoch) return;
            if (!staleScope.cancelled() || staleScope.tryCommit())
                throw new AssertionError("Expired section epoch was reported as committed");
            staleScope.close();
            result.put("expiredScopeCancelled", true);
            mc.options.renderDistance().set(savedDistance);
            mc.levelRenderer.allChanged();
            frames = 0;
            step = 25;
            return;
        }
        if (step == 25) {
            if (++frames < 10 || !settled()) return;
            finish(mc, true);
        }
    }

    private static ServerSubLevel assemble(Minecraft mc, BlockPos point) {
        return SubLevelAssemblyHelper.assembleBlocks(
                mc.getSingleplayerServer().overworld(),
                point,
                List.of(point),
                new BoundingBox3i(
                        point.getX(),
                        point.getY(),
                        point.getZ(),
                        point.getX(),
                        point.getY(),
                        point.getZ()));
    }

    private static void returnToWorld(Minecraft mc, ServerSubLevel source) {
        var anchor = source.getPlot().getCenterBlock();
        var point =
                source.logicalPose()
                        .transformPosition(
                                new org.joml.Vector3d(
                                        anchor.getX() + .5,
                                        anchor.getY() + .5,
                                        anchor.getZ() + .5));
        dev.simulated_team.simulated.util.SimAssemblyHelper.disassembleSubLevel(
                mc.getSingleplayerServer().overworld(),
                source,
                anchor,
                BlockPos.containing(point.x, point.y, point.z),
                Rotation.NONE,
                false);
    }

    private static void block(
            Minecraft mc, int offset, net.minecraft.world.level.block.state.BlockState state) {
        var p = serverSources.getFirst().getPlot().getBoundingBox();
        mc.getSingleplayerServer()
                .overworld()
                .setBlock(new BlockPos(p.minX() + offset, p.minY(), p.minZ()), state, 3);
    }

    private static boolean settled() {
        var counters = SableSceneRegistry.handoffCounters();
        return counters[2] == 0
                && counters[3]
                        == SubLevelContainer.getContainer(Minecraft.getInstance().level)
                                .getAllSubLevels()
                                .size();
    }

    private static void operate(Minecraft mc, Runnable run) {
        operation = CompletableFuture.runAsync(run, mc.getSingleplayerServer());
    }

    private static Path output() {
        return Path.of(System.getProperty("validation.result"));
    }

    private static void screenshot(Minecraft mc, String name) throws Exception {
        var method =
                Class.forName("com.radiance.audit.EntityTakeoverProbe")
                        .getDeclaredMethod("screenshot", Minecraft.class, String.class);
        method.setAccessible(true);
        method.invoke(null, mc, name);
    }

    private static void checkOverlappingScopes() throws Exception {
        var registry = SableSceneRegistry.class.getDeclaredField("ENTRIES");
        registry.setAccessible(true);
        for (Object entry : ((Map<?, ?>) registry.get(null)).values()) {
            var field = entry.getClass().getDeclaredField("sections");
            field.setAccessible(true);
            if (!(field.get(entry) instanceof SectionScene scene)) continue;
            try (var a = new SectionHandoff();
                    var b = new SectionHandoff()) {
                var prepared = new java.util.concurrent.atomic.AtomicBoolean();
                var published = new java.util.concurrent.atomic.AtomicInteger();
                a.participant(
                        new SectionHandoff.Participant() {
                            public boolean ready() {
                                return prepared.get();
                            }

                            public void publish() {
                                published.incrementAndGet();
                            }

                            public void cancel() {
                                throw new AssertionError("Committed participant was cancelled");
                            }
                        });
                a.sections(scene);
                b.join(a);
                a.seal();
                if (a.tryCommit())
                    throw new AssertionError("An unsealed related scope was bypassed");
                b.seal();
                if (a.tryCommit() || b.tryCommit())
                    throw new AssertionError(
                            "Joined terrain bypassed an unprepared visual participant");
                prepared.set(true);
                a.prepareCommit();
                if (!a.tryCommit() || !b.tryCommit())
                    throw new AssertionError("Ready overlapping scopes did not commit together");
                a.tryCommit();
                if (published.get() != 1)
                    throw new AssertionError("Participant publication was not exactly once");
            }
            result.put("overlappingScopes", true);
            checkCancelledScope(scene);
            checkRelocatedMember(Minecraft.getInstance(), scene);
            try (var discovery = new SectionHandoff()) {
                if (discovery.worldChanges(new BlockPos(64, 256, 64), new BlockPos(79, 271, 79)))
                    throw new AssertionError(
                            "Unchanged or unseen world sections entered an initial transfer");
                discovery.seal();
                if (!discovery.tryCommit())
                    throw new AssertionError(
                            "Independent initial discovery waited for world loading");
                result.put("independentInitialDiscovery", true);
            }
            return;
        }
        throw new AssertionError("No actual chunked source for overlap regression");
    }

    private static long handle(SectionHandoff scope) throws Exception {
        var field = SectionHandoff.class.getDeclaredField("handle");
        field.setAccessible(true);
        return field.getLong(scope);
    }

    private static void beginHeldUnload(Minecraft mc) throws Exception {
        var registry = SableSceneRegistry.class.getDeclaredField("ENTRIES");
        registry.setAccessible(true);
        for (var item : ((Map<?, ?>) registry.get(null)).entrySet()) {
            var field = item.getValue().getClass().getDeclaredField("sections");
            field.setAccessible(true);
            if (!(field.get(item.getValue()) instanceof SectionScene scene) || scene.size() == 0)
                continue;
            unloadingSource = ((ClientSubLevel) item.getKey()).getUniqueId();
            var spaceField = item.getValue().getClass().getDeclaredField("space");
            spaceField.setAccessible(true);
            unloadingSpace = (RenderSpace) spaceField.get(item.getValue());
            unloadingScope = new SectionHandoff();
            unloadingScope.sections(scene);
            unloadingScope.participant(participant(unloadPrepared, unloadCallbacks));
            unloadingScope.seal();
            // The server's stop-tracking packet becomes REMOVED on the client and tests a
            // world return. Exercise the actual client UNLOADED boundary instead: the source
            // remains in the server world, while its local space closes before our hold ends.
            SubLevelContainer.getContainer(mc.level)
                    .removeSubLevel(
                            (ClientSubLevel) item.getKey(),
                            dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason.UNLOADED);
            return;
        }
        throw new AssertionError("No chunked source for held-unload regression");
    }

    private static SectionHandoff.Participant participant(
            java.util.concurrent.atomic.AtomicBoolean ready, int[] counts) {
        return new SectionHandoff.Participant() {
            public boolean ready() {
                return ready.get();
            }

            public void publish() {
                counts[0]++;
            }

            public void cancel() {
                counts[1]++;
            }
        };
    }

    private static void checkCancelledScope(SectionScene scene) throws Exception {
        var prepared = new java.util.concurrent.atomic.AtomicBoolean();
        int[] cancelled = new int[2], surviving = new int[2];
        try (var a = new SectionHandoff();
                var b = new SectionHandoff()) {
            a.sections(scene);
            b.sections(scene);
            a.participant(participant(new java.util.concurrent.atomic.AtomicBoolean(), cancelled));
            b.participant(participant(prepared, surviving));
            a.seal();
            b.seal();
            long oldHandle = handle(a);
            a.close();
            if (!a.cancelled() || a.tryCommit() || ChunkProxy.commitHandoffNative(oldHandle))
                throw new AssertionError("Cancelled scope was reported as committed");
            if (ChunkProxy.commitHandoffNative(handle(b)))
                throw new AssertionError("Closing one scope published its unprepared joined scope");
            prepared.set(true);
            if (!b.tryCommit()) throw new AssertionError("Surviving scope failed to publish");
            a.close();
            if (cancelled[0] != 0 || cancelled[1] != 1 || surviving[0] != 1 || surviving[1] != 0)
                throw new AssertionError(
                        "Cancellation/publication callbacks were not independent and exactly once");
        }
        result.put("cancelledJoinedScope", true);
    }

    private static void checkRelocatedMember(Minecraft mc, SectionScene scene) throws Exception {
        var field = ChunkProxy.class.getDeclaredField("currentStorage");
        field.setAccessible(true);
        var storage = (net.minecraft.client.renderer.ViewArea) field.get(null);
        var primary = storage.sections.length == 0 ? null : storage.sections[0];
        if (primary == null) throw new AssertionError("No primary slot for relocation regression");
        var origin = primary.getOrigin().immutable();
        primary.setDirty(true);
        var prepared = new java.util.concurrent.atomic.AtomicBoolean();
        int[] callbacks = new int[2];
        try (var a = new SectionHandoff();
                var b = new SectionHandoff()) {
            a.world(origin, origin);
            b.join(a);
            b.sections(scene);
            b.participant(participant(prepared, callbacks));
            a.seal();
            b.seal();
            try {
                primary.setOrigin(origin.getX() + 16, origin.getY(), origin.getZ());
                if (primary.isDirtyFromPlayer())
                    throw new AssertionError(
                            "Relocated slot inherited the old player's dirty priority");
                var states = ChunkProxy.class.getDeclaredField("chunkBuildStates");
                states.setAccessible(true);
                var state = ((Map<?, ?>) states.get(null)).get(primary.index);
                var important = state.getClass().getDeclaredField("interactive");
                important.setAccessible(true);
                if (important.getBoolean(state))
                    throw new AssertionError(
                            "Relocated slot inherited Radiance's old interaction priority");
            } finally {
                primary.setOrigin(origin.getX(), origin.getY(), origin.getZ());
            }
            if (ChunkProxy.commitHandoffNative(handle(b)))
                throw new AssertionError("Relocating one slot published the remaining group");
            prepared.set(true);
            b.prepareCommit();
            if (!a.tryCommit() || !b.tryCommit() || callbacks[0] != 1)
                throw new AssertionError("Relocation left an unrelated joined scope stuck");
        }
        result.put("relocatedJoinedMember", true);
        result.put("relocatedPriorityCleared", true);
    }

    private static void finish(Minecraft mc, boolean passed) {
        done = true;
        result.put("transferEvents", SableSceneRegistry.transferEvents());
        result.put("flywheelFinal", FlywheelHandoffs.counters());
        result.put("passed", passed);
        result.put("mode", "handoff");
        result.put("performanceMeasured", false);
        result.put(
                "viewport",
                Map.of("width", mc.getWindow().getWidth(), "height", mc.getWindow().getHeight()));
        try {
            Files.writeString(
                    output().resolve("world-materials.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result)
                            + "\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        mc.stop();
    }
}
