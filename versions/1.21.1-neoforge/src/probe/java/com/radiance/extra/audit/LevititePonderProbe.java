package com.radiance.extra.audit;

import dev.eriksonn.aeronautics.content.blocks.levitite.LevititeShaderManager;
import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.ResourceLocation;

/** Render the actual registered tutorial at reproducible source timeline positions. */
public final class LevititePonderProbe {
    private static final int[] TIMES = {20, 160, 350, 540, 720, 900, 1100, 20};
    private static final List<Object> SAMPLES = new ArrayList<>();
    private static int index, frames;
    private static long start;
    private static Object ui;
    private static boolean done;
    private static java.util.concurrent.CompletableFuture<?> prepared;
    private static final Map<String, Long> DRAWS = new LinkedHashMap<>();

    public static void recordDraw() {
        if (!"levitite-ponder".equals(System.getProperty("radiance.audit.worldMaterials134"))
                || ui == null
                || done
                || Minecraft.getInstance().screen != ui) return;
        var shader = com.mojang.blaze3d.systems.RenderSystem.getShader();
        if (shader == null) return;
        String name =
                shader
                                instanceof
                                com.radiance.mixin_related.extensions.vulkan_render_integration
                                                .IExternalShaderProgram
                                        e
                        ? e.radiance$getExternalShader(
                                        com.mojang.blaze3d.vertex.VertexFormat.Mode.QUADS)
                                .name()
                        : shader.getName();
        DRAWS.merge(name + ":effect=" + LevititeShaderManager.isEnabled(), 1L, Long::sum);
    }

    static void poll(Minecraft mc) {
        if (!"levitite-ponder".equals(System.getProperty("radiance.audit.worldMaterials134"))
                || done
                || mc.level == null
                || mc.player == null
                || mc.getOverlay() != null) return;
        try {
            if (start == 0) start = System.nanoTime();
            if (System.nanoTime() - start > 180_000_000_000L)
                throw new AssertionError("Levitite Ponder deadline");
            if (prepared == null) {
                var server = mc.getSingleplayerServer();
                prepared =
                        server.submit(
                                () -> {
                                    var level = server.overworld();
                                    for (String command :
                                            List.of(
                                                    "gamemode spectator @a",
                                                    "tp @a 0 74 10 facing 0 72 0"))
                                        server.getCommands()
                                                .performPrefixedCommand(
                                                        server.createCommandSourceStack()
                                                                .withPermission(4)
                                                                .withSuppressedOutput(),
                                                        command);
                                    var blocks = new ArrayList<net.minecraft.core.BlockPos>();
                                    for (int y = 71; y <= 73; y++) {
                                        var pos = new net.minecraft.core.BlockPos(0, y, 0);
                                        blocks.add(pos);
                                        level.setBlock(
                                                pos,
                                                dev.eriksonn.aeronautics.index.AeroBlocks.LEVITITE
                                                        .getDefaultState(),
                                                3);
                                    }
                                    dev.ryanhcode.sable.api.SubLevelAssemblyHelper.assembleBlocks(
                                            level,
                                            blocks.getFirst(),
                                            blocks,
                                            new dev.ryanhcode.sable.companion.math.BoundingBox3i(
                                                    0, 71, 0, 0, 73, 0));
                                });
                return;
            }
            if (!prepared.isDone()) return;
            prepared.join();
            var sublevels =
                    dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
            if (sublevels != null)
                for (var sublevel : sublevels.getAllSubLevels())
                    if (sublevel instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel client) {
                        var manager = LevititeShaderManager.getInstance(client);
                        var access =
                                (com.radiance.extra.mixins.compatibility.aeronautics
                                                .LevititeMotionAccessor)
                                        manager;
                        access.radiance$linear().set(.12, .025, 0);
                        access.radiance$previousLinear().set(access.radiance$linear());
                        access.radiance$angular().set(.01, .025, .005);
                        access.radiance$previousAngular().set(access.radiance$angular());
                    }
            if (ui == null) {
                if (++frames < 40) return;
                var type = Class.forName("net.createmod.ponder.foundation.ui.PonderUI");
                ui =
                        type.getMethod("of", ResourceLocation.class)
                                .invoke(
                                        null,
                                        ResourceLocation.fromNamespaceAndPath(
                                                "aeronautics", "levitite"));
                mc.setScreen((Screen) ui);
                type.getMethod("seekToTime", int.class).invoke(ui, TIMES[0]);
                frames = 0;
                return;
            }
            if (++frames < 30) return;
            var scene = ui.getClass().getMethod("getActiveScene").invoke(ui);
            var record = new LinkedHashMap<String, Object>();
            record.put("requestedTime", TIMES[index]);
            record.put("currentTime", scene.getClass().getMethod("getCurrentTime").invoke(scene));
            record.put("sourceEffectEnabled", LevititeShaderManager.isEnabled());
            record.put("rasterDraws", new LinkedHashMap<>(DRAWS));
            record.put(
                    "worldSurfaces",
                    com.radiance.extra.compatibility.aeronautics.LevititeWorldAdapter.counters());
            SAMPLES.add(record);
            var screenshot =
                    Class.forName("com.radiance.audit.EntityTakeoverProbe")
                            .getDeclaredMethod("screenshot", Minecraft.class, String.class);
            screenshot.setAccessible(true);
            screenshot.invoke(null, mc, "levitite-ponder-" + index + "-" + TIMES[index] + ".png");
            if (++index == TIMES.length) {
                finish(mc, null);
                return;
            }
            ui.getClass().getMethod("seekToTime", int.class).invoke(ui, TIMES[index]);
            frames = 0;
        } catch (Throwable failure) {
            com.mojang.logging.LogUtils.getLogger().error("Levitite Ponder check failed", failure);
            finish(mc, failure.toString());
        }
    }

    private static void finish(Minecraft mc, String error) {
        done = true;
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", error == null);
        result.put("error", error);
        result.put("mode", "levitite-ponder");
        result.put("samples", SAMPLES);
        result.put("visualAcceptance", "pending");
        result.put("performanceMeasured", false);
        try {
            Files.writeString(
                    Path.of(System.getProperty("validation.result"))
                            .resolve("world-materials.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result)
                            + "\n");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        if (mc.screen != null) mc.screen.onClose();
        mc.stop();
    }
}
