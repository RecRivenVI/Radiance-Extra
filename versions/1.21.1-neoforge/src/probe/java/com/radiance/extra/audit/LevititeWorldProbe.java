package com.radiance.extra.audit;

import com.radiance.extra.compatibility.aeronautics.LevititeWorldAdapter;
import com.radiance.extra.mixins.compatibility.aeronautics.LevititeMotionAccessor;
import dev.eriksonn.aeronautics.content.blocks.levitite.LevititeShaderManager;
import dev.eriksonn.aeronautics.index.AeroBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Owned functional scene, not a physics benchmark or a substitute for visual acceptance. */
final class LevititeWorldProbe {
    private static int step, frames;
    private static int edit, observedEditFrames;
    private static long start, definitions;
    private static boolean done;
    private static CompletableFuture<?> pending;
    private static final Map<String, Object> RESULT = new LinkedHashMap<>();

    static void poll(Minecraft mc) {
        if (!"levitite".equals(System.getProperty("radiance.audit.worldMaterials134"))
                || done
                || mc.level == null
                || mc.player == null
                || mc.getOverlay() != null) return;
        try {
            if (start == 0) start = System.nanoTime();
            if (System.nanoTime() - start > 180_000_000_000L)
                throw new AssertionError("Levitite deadline, step " + step);
            if (pending != null) {
                if (!pending.isDone()) return;
                pending.join();
                pending = null;
            }
            if (step == 0) {
                var server = mc.getSingleplayerServer();
                pending =
                        server.submit(
                                () -> {
                                    var level = server.overworld();
                                    for (String command :
                                            List.of(
                                                    "gamerule doMobSpawning false",
                                                    "gamerule doDaylightCycle false",
                                                    "time set noon",
                                                    "weather clear",
                                                    "gamemode spectator @a",
                                                    "tp @a 4 74 12 facing 4 73 0",
                                                    "fill -9 69 -6 9 69 6 minecraft:stone",
                                                    "fill -9 70 -6 9 75 6 minecraft:air",
                                                    "fill -4 70 4 9 76 4 minecraft:glass"))
                                        server.getCommands()
                                                .performPrefixedCommand(
                                                        server.createCommandSourceStack()
                                                                .withPermission(4)
                                                                .withSuppressedOutput(),
                                                        command);
                                    level.setBlock(
                                            new BlockPos(-5, 71, 0),
                                            AeroBlocks.LEVITITE.getDefaultState(),
                                            3);
                                    level.setBlock(
                                            new BlockPos(-4, 71, 0),
                                            AeroBlocks.PEARLESCENT_LEVITITE.getDefaultState(),
                                            3);
                                    for (int x : new int[] {0, 4, 8}) {
                                        var a = new BlockPos(x, 72, 0);
                                        var blocks = new ArrayList<BlockPos>();
                                        for (int dy = 0; dy < 1 + x / 4; dy++) {
                                            var pos = a.above(dy);
                                            blocks.add(pos);
                                            level.setBlock(
                                                    pos,
                                                    (dy % 2 == 0
                                                                    ? AeroBlocks.LEVITITE
                                                                    : AeroBlocks
                                                                            .PEARLESCENT_LEVITITE)
                                                            .getDefaultState(),
                                                    3);
                                        }
                                        SubLevelAssemblyHelper.assembleBlocks(
                                                level,
                                                a,
                                                blocks,
                                                new BoundingBox3i(
                                                        x, 72, 0, x, 72 + blocks.size() - 1, 0));
                                    }
                                });
                step = 1;
                return;
            }
            var container = SubLevelContainer.getContainer(mc.level);
            if (container == null || container.getAllSubLevels().size() < 3) return;
            // Exercise the actual shader-manager seam at controlled linear/angular states.
            // The real source pose still comes from Sable. This is not a physical-motion proof.
            for (var source : container.getAllSubLevels())
                if (source instanceof ClientSubLevel client) {
                    var manager =
                            LevititeShaderManager.managers.computeIfAbsent(
                                    client, k -> new LevititeShaderManager());
                    var access = (LevititeMotionAccessor) manager;
                    double sign = step == 2 ? -1 : 1;
                    access.radiance$linear().set(sign * .12, .025, 0);
                    access.radiance$previousLinear().set(access.radiance$linear());
                    access.radiance$angular().set(.01, sign * .025, .005);
                    access.radiance$previousAngular().set(access.radiance$angular());
                }
            long[] counts = LevititeWorldAdapter.counters();
            if (step == 1 && counts[0] > 60 && counts[1] > 60 && ++frames > 80) {
                RESULT.put("first", counts);
                geometrySnapshot(mc, "geometry-forward.json");
                definitions = counts[2];
                screenshot(mc, "levitite-forward-glass.png");
                frames = 0;
                step = 2;
                return;
            }
            if (step == 2 && ++frames > 40) {
                if (counts[2] != definitions)
                    throw new AssertionError("Stable source redefined its model");
                RESULT.put("stableModelDefinitions", true);
                geometrySnapshot(mc, "geometry-reverse.json");
                screenshot(mc, "levitite-reverse-glass.png");
                var server = mc.getSingleplayerServer();
                pending =
                        server.submit(
                                () -> {
                                    for (String command :
                                            List.of(
                                                    "fill -4 70 4 9 76 4 minecraft:air",
                                                    "tp @a 4 74 7 facing 4 73 0"))
                                        server.getCommands()
                                                .performPrefixedCommand(
                                                        server.createCommandSourceStack()
                                                                .withPermission(4)
                                                                .withSuppressedOutput(),
                                                        command);
                                });
                frames = 0;
                step = 3;
                return;
            }
            if (step == 3 && ++frames > 40) {
                geometrySnapshot(mc, "geometry-direct.json");
                screenshot(mc, "levitite-direct.png");
                frames = 0;
                step = 30;
                return;
            }
            if (step == 30) {
                if (!hasMesh(new BlockPos(-16, 64, 0)))
                    throw new AssertionError(
                            "Unchanged world crystal vanished during section edit " + edit);
                observedEditFrames++;
                if (++frames < 24) return;
                frames = 0;
                if (edit < 8) {
                    boolean place = (edit++ & 1) == 0;
                    pending =
                            mc.getSingleplayerServer()
                                    .submit(
                                            () ->
                                                    mc.getSingleplayerServer()
                                                            .overworld()
                                                            .setBlock(
                                                                    new BlockPos(-7, 71, 1),
                                                                    (place
                                                                                    ? net.minecraft
                                                                                            .world
                                                                                            .level
                                                                                            .block
                                                                                            .Blocks
                                                                                            .STONE
                                                                                    : net.minecraft
                                                                                            .world
                                                                                            .level
                                                                                            .block
                                                                                            .Blocks
                                                                                            .AIR)
                                                                            .defaultBlockState(),
                                                                    3));
                } else {
                    RESULT.put("sectionEditFrames", observedEditFrames);
                    RESULT.put("sectionEditContinuity", true);
                    pending =
                            mc.getSingleplayerServer()
                                    .submit(
                                            () -> {
                                                var level = mc.getSingleplayerServer().overworld();
                                                level.setBlock(
                                                        new BlockPos(-5, 71, 0),
                                                        net.minecraft.world.level.block.Blocks.AIR
                                                                .defaultBlockState(),
                                                        3);
                                                level.setBlock(
                                                        new BlockPos(-4, 71, 0),
                                                        net.minecraft.world.level.block.Blocks.AIR
                                                                .defaultBlockState(),
                                                        3);
                                            });
                    step = 31;
                }
                return;
            }
            if (step == 31) {
                if (hasMesh(new BlockPos(-16, 64, 0))) return;
                RESULT.put("removedLastRetainedLayer", true);
                pending =
                        mc.getSingleplayerServer()
                                .submit(
                                        () ->
                                                mc.getSingleplayerServer()
                                                        .overworld()
                                                        .setBlock(
                                                                new BlockPos(-6, 99, 1),
                                                                AeroBlocks.LEVITITE
                                                                        .getDefaultState(),
                                                                3));
                step = 32;
                return;
            }
            if (step == 32) {
                if (!hasMesh(new BlockPos(-16, 96, 0))) return;
                pending =
                        mc.getSingleplayerServer()
                                .submit(
                                        () ->
                                                mc.getSingleplayerServer()
                                                        .overworld()
                                                        .setBlock(
                                                                new BlockPos(-6, 99, 1),
                                                                net.minecraft.world.level.block
                                                                        .Blocks.AIR
                                                                        .defaultBlockState(),
                                                                3));
                step = 33;
                return;
            }
            if (step == 33) {
                if (hasMesh(new BlockPos(-16, 96, 0))) return;
                RESULT.put("removedNowEmptySection", true);
                pending = mc.reloadResourcePacks();
                frames = 0;
                step = 4;
                return;
            }
            if (step == 4 && ++frames > 60) {
                if (counts[2] <= definitions || counts[0] < 100 || counts[1] < 100)
                    throw new AssertionError("Reload did not resume all PT layers");
                RESULT.put("afterReload", counts);
                geometrySnapshot(mc, "geometry-reloaded.json");
                RESULT.put(
                        "sublevelRenderData",
                        container.getAllSubLevels().stream()
                                .map(s -> ((ClientSubLevel) s).getRenderData().getClass().getName())
                                .toList());
                screenshot(mc, "levitite-reloaded.png");
                finish(mc, true);
            }
        } catch (Throwable e) {
            com.mojang.logging.LogUtils.getLogger().error("Levitite functional check failed", e);
            RESULT.put("error", e.toString());
            finish(mc, false);
        }
    }

    private static boolean hasMesh(BlockPos origin) throws Exception {
        var meshes = LevititeWorldAdapter.class.getDeclaredField("MESHES");
        meshes.setAccessible(true);
        for (var mesh : ((Map<?, ?>) meshes.get(null)).values())
            if (origin.equals(field(mesh, "origin"))) return true;
        return false;
    }

    private static void screenshot(Minecraft mc, String name) throws Exception {
        var method =
                Class.forName("com.radiance.audit.EntityTakeoverProbe")
                        .getDeclaredMethod("screenshot", Minecraft.class, String.class);
        method.setAccessible(true);
        method.invoke(null, mc, name);
    }

    private static Object field(Object object, String name) throws Exception {
        var declared = object.getClass().getDeclaredField(name);
        declared.setAccessible(true);
        return declared.get(object);
    }

    private static void geometrySnapshot(Minecraft mc, String name) throws Exception {
        var declared = LevititeWorldAdapter.class.getDeclaredField("MESHES");
        declared.setAccessible(true);
        var meshes = (Map<?, ?>) declared.get(null);
        var output = new ArrayList<Map<String, Object>>();
        for (var mesh : meshes.values()) {
            var record = new LinkedHashMap<String, Object>();
            var origin = (BlockPos) field(mesh, "origin");
            record.put("origin", List.of(origin.getX(), origin.getY(), origin.getZ()));
            record.put("ghost", false);
            record.put("count", field(mesh, "count"));
            var source = dev.ryanhcode.sable.Sable.HELPER.getContaining(mc.level, origin);
            if (source instanceof ClientSubLevel client) {
                var pose = client.renderPose(mc.getTimer().getGameTimeDeltaPartialTick(true));
                var p = pose.position();
                var r = pose.rotationPoint();
                var q = pose.orientation();
                record.put("centre", List.of(p.x(), p.y(), p.z()));
                record.put("rotationPoint", List.of(r.x(), r.y(), r.z()));
                record.put("orientation", List.of(q.x(), q.y(), q.z(), q.w()));
            }
            var buffer =
                    ((java.nio.ByteBuffer) field(mesh, "geometry"))
                            .duplicate()
                            .order(java.nio.ByteOrder.nativeOrder());
            var vertices = new ArrayList<Object>();
            int degenerate = 0;
            for (int i = 0; i < (int) field(mesh, "count"); i++) {
                int at = i * 128;
                vertices.add(
                        List.of(
                                buffer.getFloat(at),
                                buffer.getFloat(at + 4),
                                buffer.getFloat(at + 8),
                                buffer.getFloat(at + 44)));
            }
            for (int i = 0; i < (int) field(mesh, "count"); i += 4) {
                var a = point(buffer, i);
                var b = point(buffer, i + 1);
                var c = point(buffer, i + 2);
                var d = point(buffer, i + 3);
                if (new org.joml.Vector3f(b)
                                .sub(a)
                                .cross(new org.joml.Vector3f(c).sub(a))
                                .lengthSquared()
                        < 1e-12) degenerate++;
                if (new org.joml.Vector3f(d)
                                .sub(c)
                                .cross(new org.joml.Vector3f(a).sub(c))
                                .lengthSquared()
                        < 1e-12) degenerate++;
            }
            int width = (int) field(mesh, "width"), height = (int) field(mesh, "height");
            var scalar = (com.radiance.api.render.RenderResources.Texture) field(mesh, "field");
            var readback =
                    org.lwjgl.system.MemoryUtil.memAlloc(width * height * 4)
                            .order(java.nio.ByteOrder.nativeOrder());
            String stem = name.replace(".json", "") + "-" + output.size();
            try {
                com.radiance.client.proxy.vulkan.TextureProxy.downloadTexture(
                        scalar.id(),
                        0,
                        width,
                        height,
                        4,
                        org.lwjgl.system.MemoryUtil.memAddress(readback));
                var target = Path.of(System.getProperty("validation.result"));
                for (var item :
                        Map.of(
                                        "field",
                                        readback,
                                        "params",
                                        (java.nio.ByteBuffer) field(mesh, "parameters"),
                                        "source",
                                        buffer)
                                .entrySet()) {
                    byte[] bytes = new byte[item.getValue().remaining()];
                    item.getValue().get(item.getValue().position(), bytes);
                    Files.write(target.resolve(stem + "-" + item.getKey() + ".bin"), bytes);
                }
                record.put("fieldReadback", stem);
                record.put("fieldWidth", width);
                record.put("fieldHeight", height);
            } finally {
                org.lwjgl.system.MemoryUtil.memFree(readback);
            }
            record.put("degenerateTriangles", degenerate);
            record.put("finalPositionsAlpha", vertices);
            output.add(record);
        }
        Files.writeString(
                Path.of(System.getProperty("validation.result")).resolve(name),
                new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(output)
                        + "\n");
        var sourceBodies = output.stream().filter(m -> m.containsKey("rotationPoint")).toList();
        if (sourceBodies.size() != 3
                || !sourceBodies.stream()
                        .map(m -> (int) m.get("count"))
                        .collect(java.util.stream.Collectors.toSet())
                        .equals(Set.of(24, 40, 56)))
            throw new AssertionError("Missing original one/two/three-block body geometry");
        if (sourceBodies.stream().anyMatch(m -> (int) m.get("degenerateTriangles") != 0))
            throw new AssertionError("Source body collapsed");
        // Deformed positions are GPU outputs, checked by verify_computed_surfaces.py from
        // fence-retired readbacks.
    }

    private static org.joml.Vector3f point(java.nio.ByteBuffer buffer, int vertex) {
        int at = vertex * 128;
        return new org.joml.Vector3f(
                buffer.getFloat(at), buffer.getFloat(at + 4), buffer.getFloat(at + 8));
    }

    private static void finish(Minecraft mc, boolean passed) {
        done = true;
        RESULT.put("passed", passed);
        RESULT.put("mode", "levitite");
        RESULT.put("sourceVelocityInjected", true);
        RESULT.put("performanceMeasured", false);
        RESULT.put("visualAcceptance", "pending");
        try {
            Files.writeString(
                    Path.of(System.getProperty("validation.result"))
                            .resolve("world-materials.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(RESULT)
                            + "\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        mc.stop();
    }
}
