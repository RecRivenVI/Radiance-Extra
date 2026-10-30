package com.radiance.extra.compatibility.catnip;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.*;
import com.radiance.render.contract.DrawStateSnapshot.UvTransform;
import net.createmod.catnip.render.*;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.metadata.animation.FrameSize;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.junit.jupiter.api.Test;

class CatnipTemplateGeometryTest {
    private static final ResourceLocation ATLAS =
            ResourceLocation.withDefaultNamespace("test_atlas");

    private static final class Sprite extends TextureAtlasSprite {
        Sprite(SpriteContents contents, int x, int y) {
            super(ATLAS, contents, 256, 256, x, y);
        }
    }

    @Test
    void affineShiftAndSheetKeepSourceAtlasMappingIncludingOutsideSpriteUvs() {
        try (var from =
                        new SpriteContents(
                                ATLAS,
                                new FrameSize(16, 16),
                                new NativeImage(16, 16, true),
                                ResourceMetadata.EMPTY);
                var to =
                        new SpriteContents(
                                ATLAS,
                                new FrameSize(32, 16),
                                new NativeImage(32, 16, true),
                                ResourceMetadata.EMPTY)) {
            var sourceSprite = new Sprite(from, 32, 64);
            var destinationSprite = new Sprite(to, 128, 160);
            var entry =
                    new SpriteShiftEntry() {
                        public TextureAtlasSprite getOriginal() {
                            return sourceSprite;
                        }

                        public TextureAtlasSprite getTarget() {
                            return destinationSprite;
                        }
                    };
            for (float local : new float[] {-0.2f, 0, 0.3f, 1, 1.2f}) {
                float u = sourceSprite.getU(local), v = sourceSprite.getV(local);
                var shift = CatnipTemplateGeometry.shift(entry, 0, 0, 1);
                assertEquals(entry.getTargetU(u), shift.uu() * u + shift.uOffset(), 1e-6f);
                assertEquals(entry.getTargetV(v), shift.vv() * v + shift.vOffset(), 1e-6f);
                float tileU = 0.25f, tileV = 0.5f;
                var sheet =
                        CatnipTemplateGeometry.shift(
                                entry,
                                tileU * (destinationSprite.getU1() - destinationSprite.getU0()),
                                tileV * (destinationSprite.getV1() - destinationSprite.getV0()),
                                0.25f);
                assertEquals(
                        destinationSprite.getU(local / 4 + tileU),
                        sheet.uu() * u + sheet.uOffset(),
                        1e-6f);
                assertEquals(
                        destinationSprite.getV(local / 4 + tileV),
                        sheet.vv() * v + sheet.vOffset(),
                        1e-6f);
            }
        }
    }

    @Test
    void templatePreservesColorAlphaOverlayAndNormalsWithoutReceivedLighting() {
        int[] data = new int[4 * TemplateMesh.INT_STRIDE];
        for (int i = 0; i < 4; i++) {
            int at = i * TemplateMesh.INT_STRIDE;
            data[at] = Float.floatToRawIntBits(0.3f + i);
            data[at + 1] = Float.floatToRawIntBits(-0.75f);
            data[at + 2] = Float.floatToRawIntBits(2.5f);
            data[at + 3] = 0x80402010;
            data[at + 4] = Float.floatToRawIntBits(0.125f);
            data[at + 5] = Float.floatToRawIntBits(0.875f);
            data[at + 6] = 0x00120034;
            data[at + 7] = 0x00F000F0;
            data[at + 8] = 0x007f0081;
        }
        var out = new Recording();
        CatnipTemplateGeometry.emit(new TemplateMesh(data), new PoseStack().last(), out);
        assertEquals(4, out.vertices);
        assertEquals(3.3f, out.x);
        assertEquals(0x80402010, out.color);
        assertEquals(0.125f, out.u);
        assertEquals(0.875f, out.v);
        assertEquals(0x00120034, out.overlay);
        assertEquals(0, out.light);
        assertEquals(-1, out.nx);
        assertEquals(1, out.nz);
    }

    @Test
    void unsupportedConsumerOrMutableTemplateIsUntouched() {
        var out = new Recording();
        for (var mesh : new TemplateMesh[] {new TemplateMesh(4), new MutableTemplateMesh(4)})
            assertFalse(
                    CatnipTemplateGeometry.submit(
                            mesh,
                            new PoseStack().last(),
                            out,
                            1,
                            1,
                            1,
                            1,
                            false,
                            0,
                            UvTransform.IDENTITY));
        assertEquals(0, out.vertices);
    }

    private static final class Recording implements VertexConsumer {
        int vertices, color, light, overlay;
        float x, u, v, nx, nz;

        public VertexConsumer addVertex(float x, float y, float z) {
            vertices++;
            this.x = x;
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            color = r | g << 8 | b << 16 | a << 24;
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            this.u = u;
            this.v = v;
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            overlay = u | v << 16;
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            light = u | v << 16;
            return this;
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            nx = x;
            nz = z;
            return this;
        }
    }
}
