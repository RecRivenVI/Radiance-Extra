package com.radiance.extra.audit;

import java.nio.file.*;
import java.util.*;
import net.minecraft.client.Minecraft;

/** On-demand source presence in the packaged platform fixture; no timing instrumentation. */
final class PlatformSourceProbe {
    private static long start;
    private static boolean done;

    static void poll(Minecraft mc) {
        if (!Boolean.getBoolean("radiance.audit.platform") || done || mc.level == null) return;
        if (start == 0) {
            start = System.nanoTime();
            return;
        }
        if (System.nanoTime() - start < 44_000_000_000L) return;
        var container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(mc.level);
        int structures = container == null ? 0 : container.getAllSubLevels().size();
        var result =
                Map.of(
                        "sableSublevels",
                        structures,
                        "registeredSources",
                        com.radiance.api.render.RenderPlatform.integrations(),
                        "performanceMeasured",
                        false);
        try {
            Files.writeString(
                    Path.of(System.getProperty("validation.result"), "extra-platform-sources.json"),
                    new com.google.gson.Gson().toJson(result) + "\n");
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException(failure);
        }
        if (structures == 0)
            throw new IllegalStateException(
                    "No actual Sable structure in the combined platform fixture");
        if (Boolean.getBoolean("radiance.audit.sableEntitySpaces"))
            try {
                checkStateExtensions();
                checkSourceShading();
                SceneLifecycleProbe.checkEntitySpaces(mc);
            } catch (Exception failure) {
                throw new IllegalStateException("Sable entity space regression", failure);
            }
        done = true;
    }

    private static void checkSourceShading() throws Exception {
        var normals = new ArrayList<org.joml.Vector3f>();
        var delegate =
                (com.mojang.blaze3d.vertex.VertexConsumer)
                        java.lang.reflect.Proxy.newProxyInstance(
                                PlatformSourceProbe.class.getClassLoader(),
                                new Class<?>[] {com.mojang.blaze3d.vertex.VertexConsumer.class},
                                (proxy, method, args) -> {
                                    if (method.isDefault())
                                        return java.lang.reflect.InvocationHandler.invokeDefault(
                                                proxy, method, args);
                                    if (method.getName().equals("setNormal"))
                                        normals.add(
                                                new org.joml.Vector3f(
                                                        (float) args[0],
                                                        (float) args[1],
                                                        (float) args[2]));
                                    return method.getReturnType() == void.class ? null : proxy;
                                });
        var wrapped = new dev.ryanhcode.sable.render.dynamic_shade.SubLevelVertexConsumer(delegate);
        var quad =
                new net.minecraft.client.renderer.block.model.BakedQuad(
                        new int[32], -1, net.minecraft.core.Direction.EAST, null, false);
        for (boolean world : new boolean[] {false, true}) {
            normals.clear();
            com.radiance.api.render.SourceHooks.bulkConsumer(wrapped, world)
                    .putBulkData(
                            new com.mojang.blaze3d.vertex.PoseStack().last(),
                            quad,
                            new float[] {1, 1, 1, 1},
                            1,
                            1,
                            1,
                            1,
                            new int[4],
                            0,
                            true);
            var expected = world ? new org.joml.Vector3f(1, 0, 0) : new org.joml.Vector3f(0, 1, 0);
            if (normals.size() != 4 || normals.stream().anyMatch(n -> !n.equals(expected, 1e-6F)))
                throw new AssertionError(
                        "Source normal policy differs for world=" + world + ": " + normals);
        }
        Files.writeString(
                Path.of(System.getProperty("validation.result"), "sable-world-normals.txt"),
                "World retained actual EAST normal; raster preview retained source upward normal.\n");
    }

    private static void checkStateExtensions() throws Exception {
        var layer =
                net.minecraft.client.renderer.RenderType.entityCutoutNoCull(
                        net.minecraft.resources.ResourceLocation.withDefaultNamespace(
                                "textures/entity/cow/cow.png"));
        var composite = foundry.veil.api.client.render.rendertype.VeilRenderType.getShards(layer);
        var field = composite.getClass().getDeclaredField("states");
        field.setAccessible(true);
        var previous = field.get(composite);
        if (!com.radiance.api.render.SourceHooks.supportsStaticState(layer))
            throw new AssertionError("Reviewed default shards rejected");
        try {
            ((foundry.veil.ext.CompositeStateExtension) (Object) composite)
                    .veil$addShards(
                            List.of(
                                    new net.minecraft.client.renderer.RenderStateShard(
                                            "audit_unknown", () -> {}, () -> {}) {}));
            if (com.radiance.api.render.SourceHooks.supportsStaticState(layer))
                throw new AssertionError("Unknown appended shard was bypassed");
        } finally {
            field.set(composite, previous);
        }
        if (!com.radiance.api.render.SourceHooks.supportsStaticState(layer))
            throw new AssertionError("Restored source shards stayed rejected");
        Files.writeString(
                Path.of(System.getProperty("validation.result"), "veil-state-extensions.txt"),
                "Reviewed default shards accepted; arbitrary appended state rejected; source state restored.\n");
    }
}
