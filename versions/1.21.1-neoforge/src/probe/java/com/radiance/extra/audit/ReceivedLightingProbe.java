package com.radiance.extra.audit;

import com.mojang.blaze3d.vertex.*;
import com.radiance.client.render.RasterPreviewScope;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.decoration.slidingDoor.*;
import dev.ryanhcode.sable.mixinterface.dynamic_directional_shading.ModelBlockRendererCacheExtension;
import dev.ryanhcode.sable.render.dynamic_shade.SableDynamicDirectionalShading;
import dev.ryanhcode.sable.render.dynamic_shade.SubLevelVertexConsumer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.SingleBlockSubLevelWrapper;
import java.util.*;
import java.util.function.Consumer;
import net.createmod.catnip.animation.LerpedFloat;
import net.createmod.catnip.render.CachedBuffers;
import net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.*;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.neoforge.client.model.data.ModelData;

/** Samples real source output before PT lighting; no image/FPS inference from these values. */
final class ReceivedLightingProbe {
    static Map<String, Object> sample(Minecraft mc, ClientSubLevel source) throws Exception {
        var report = new LinkedHashMap<String, Object>();
        var box = source.getPlot().getBoundingBox();
        var pos = new BlockPos(box.minX(), box.minY(), box.minZ());
        var cacheField = ModelBlockRenderer.class.getDeclaredField("CACHE");
        cacheField.setAccessible(true);
        var cache =
                (ModelBlockRendererCacheExtension) ((ThreadLocal<?>) cacheField.get(null)).get();
        boolean enabled = SableDynamicDirectionalShading.isEnabled();
        boolean onSource = cache.sable$getOnSubLevel();
        try {
            for (boolean sublevel : new boolean[] {false, true}) {
                SableDynamicDirectionalShading.setIsEnabled(sublevel);
                cache.sable$setOnSubLevel(sublevel);
                for (Direction facing : Direction.Plane.HORIZONTAL) {
                    var state =
                            AllBlocks.ANDESITE_DOOR
                                    .getDefaultState()
                                    .setValue(DoorBlock.FACING, facing);
                    String key = (sublevel ? "sublevel/" : "world/") + facing;
                    unlit(report, key + "/static", out -> tessellate(mc, source, pos, state, out));
                    unlit(
                            report,
                            key + "/cached",
                            out -> CachedBuffers.block(state).renderInto(new PoseStack(), out));
                    unlit(
                            report,
                            key + "/wrapped-cached",
                            out ->
                                    CachedBuffers.block(state)
                                            .renderInto(
                                                    new PoseStack(),
                                                    new SubLevelVertexConsumer(out)));
                    unlit(
                            report,
                            key + "/nested-cached",
                            out ->
                                    CachedBuffers.block(state)
                                            .renderInto(
                                                    new PoseStack(),
                                                    new SubLevelVertexConsumer(
                                                            new SubLevelVertexConsumer(out))));
                    var moving =
                            state.setValue(SlidingDoorBlock.VISIBLE, false)
                                    .setValue(DoorBlock.OPEN, true);
                    var entity =
                            (SlidingDoorBlockEntity)
                                    ((SlidingDoorBlock) moving.getBlock())
                                            .newBlockEntity(pos, moving);
                    entity.setLevel(mc.level);
                    var animation = SlidingDoorBlockEntity.class.getDeclaredField("animation");
                    animation.setAccessible(true);
                    ((LerpedFloat) animation.get(entity)).startWithValue(.5);
                    Consumer<VertexConsumer> renderMoving =
                            out -> {
                                mc.getBlockEntityRenderDispatcher()
                                        .getRenderer(entity)
                                        .render(
                                                entity,
                                                1,
                                                new PoseStack(),
                                                layer -> out,
                                                0x00f000f0,
                                                OverlayTexture.NO_OVERLAY);
                            };
                    unlit(report, key + "/moving", renderMoving);
                    unlit(
                            report,
                            key + "/wrapped-moving",
                            out -> renderMoving.accept(new SubLevelVertexConsumer(out)));
                    try (var preview = RasterPreviewScope.enter()) {
                        var out = new Output();
                        tessellate(mc, source, pos, state, out);
                        report.put(key + "/preview", out.summary());
                        var cached = new Output();
                        CachedBuffers.block(state).renderInto(new PoseStack(), cached);
                        var wrapped = new Output();
                        CachedBuffers.block(state)
                                .renderInto(new PoseStack(), new SubLevelVertexConsumer(wrapped));
                        if (!cached.summary().equals(wrapped.summary()))
                            throw new AssertionError(
                                    "Preview wrapper changed source attributes: " + key);
                        report.put(key + "/cached-preview", cached.summary());
                        report.put(key + "/wrapped-preview", wrapped.summary());
                    }
                }
            }
            checkAttributes(report);
        } finally {
            SableDynamicDirectionalShading.setIsEnabled(enabled);
            cache.sable$setOnSubLevel(onSource);
        }
        return report;
    }

    private static void unlit(
            Map<String, Object> report, String key, Consumer<VertexConsumer> draw) {
        try (var out = new PtOutput()) {
            draw.accept(out);
            if (!out.recording.colours.equals(Set.of("255,255,255,255")))
                throw new AssertionError(
                        "Received raster lighting in " + key + ": " + out.recording.colours);
            report.put(key, out.summary());
        }
    }

    private static void checkAttributes(Map<String, Object> report) {
        int[] data = new int[4 * TemplateMesh.INT_STRIDE];
        for (int i = 0; i < 4; i++) {
            int at = i * TemplateMesh.INT_STRIDE;
            data[at] = Float.floatToIntBits(i == 1 || i == 2 ? 1 : 0);
            data[at + 1] = Float.floatToIntBits(i >= 2 ? 1 : 0);
            data[at + 3] = -1;
            data[at + 4] = Float.floatToIntBits(.25f * i);
            data[at + 5] = Float.floatToIntBits(.125f * i);
            data[at + 8] = 0x007f0000;
        }
        var mesh = new TemplateMesh(data);
        Map<String, Object> reference;
        try (var out = new PtOutput()) {
            new ShadeSeparatingSuperByteBuffer(mesh)
                    .color(80, 120, 160, 96)
                    .overlay(0x00030007)
                    .renderInto(new PoseStack(), out);
            reference = out.summary();
            if (!out.recording.colours.equals(Set.of("80,120,160,96"))
                    || !out.recording.overlays.equals(Set.of("7,3")))
                throw new AssertionError("Source tint/alpha/overlay was lost");
        }
        try (var out = new PtOutput()) {
            new ShadeSeparatingSuperByteBuffer(mesh)
                    .color(80, 120, 160, 96)
                    .overlay(0x00030007)
                    .renderInto(
                            new PoseStack(),
                            new SubLevelVertexConsumer(new SubLevelVertexConsumer(out)));
            if (!reference.equals(out.summary()))
                throw new AssertionError("PT source wrapper changed non-lighting attributes");
            report.put("source-attributes", out.summary());
        }
    }

    private static void tessellate(
            Minecraft mc,
            ClientSubLevel source,
            BlockPos pos,
            BlockState state,
            VertexConsumer out) {
        var wrapper = new SingleBlockSubLevelWrapper();
        wrapper.setup(mc.level, 0, 71, 0, pos, state);
        var model = mc.getBlockRenderer().getBlockModel(state);
        try {
            for (DoubleBlockHalf half : DoubleBlockHalf.values()) {
                var piece = state.setValue(DoorBlock.HALF, half);
                model = mc.getBlockRenderer().getBlockModel(piece);
                mc.getBlockRenderer()
                        .getModelRenderer()
                        .tesselateBlock(
                                wrapper,
                                model,
                                piece,
                                pos,
                                new PoseStack(),
                                out,
                                false,
                                RandomSource.create(),
                                piece.getSeed(pos),
                                OverlayTexture.NO_OVERLAY,
                                ModelData.EMPTY,
                                RenderType.cutoutMipped());
            }
        } finally {
            wrapper.clear();
        }
    }

    private static class Output implements VertexConsumer {
        int vertices;
        final Set<String> colours = new TreeSet<>(),
                normals = new TreeSet<>(),
                uvs = new TreeSet<>(),
                overlays = new TreeSet<>();

        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            colours.add(r + "," + g + "," + b + "," + a);
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            uvs.add(u + "," + v);
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            overlays.add(u + "," + v);
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            normals.add(x + "," + y + "," + z);
            return this;
        }

        Map<String, Object> summary() {
            if (vertices == 0) throw new AssertionError("Source produced no geometry");
            return Map.of(
                    "vertices",
                    vertices,
                    "colours",
                    colours,
                    "normals",
                    normals,
                    "uvs",
                    uvs,
                    "overlays",
                    overlays);
        }
    }

    private static final class PtOutput extends PBRVertexConsumer implements AutoCloseable {
        final Output recording = new Output();
        final ByteBufferBuilder allocation;

        PtOutput() {
            this(new ByteBufferBuilder(1024));
        }

        private PtOutput(ByteBufferBuilder allocation) {
            super(allocation, RenderType.cutoutMipped());
            this.allocation = allocation;
        }

        @Override
        public VertexConsumer addVertex(float x, float y, float z) {
            recording.addVertex(x, y, z);
            return this;
        }

        @Override
        public VertexConsumer setColor(int r, int g, int b, int a) {
            recording.setColor(r, g, b, a);
            return this;
        }

        @Override
        public VertexConsumer setUv(float u, float v) {
            recording.setUv(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv1(int u, int v) {
            recording.setUv1(u, v);
            return this;
        }

        @Override
        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        @Override
        public VertexConsumer setNormal(float x, float y, float z) {
            recording.setNormal(x, y, z);
            return this;
        }

        Map<String, Object> summary() {
            return recording.summary();
        }

        @Override
        public void close() {
            allocation.close();
        }
    }
}
