package com.radiance.extra.compatibility.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

class VoxyGeometryTest {
    @Test
    void sourceKeyIsDecodedBeforeEnteringTheNativeCanonicalSpace() {
        for (int lod = 0; lod <= 4; lod++)
            for (int x : new int[] {-33, -1, 0, 17}) {
                int y = -2, z = 41;
                long source =
                        ((long) lod << 60)
                                | ((long) (y & 255) << 52)
                                | ((long) (z & 0xffffff) << 28)
                                | ((long) (x & 0xffffff) << 4);
                assertEquals(
                        com.radiance.api.render.LodScene.node(lod, x, y, z),
                        VoxyGeometry.key(source));
            }
    }

    @Test
    void packedSourceQuadBecomesOneCanonicalRectangleWithoutVertexExpansion() {
        ByteBuffer source = MemoryUtil.memAlloc(8);
        ByteBuffer canonical = null;
        try {
            long packed =
                    5L
                            | (15L << 3)
                            | (7L << 7)
                            | (3L << 21)
                            | (5L << 16)
                            | (7L << 11)
                            | (123L << 26)
                            | (67L << 46);
            source.putLong(0, packed);
            canonical = VoxyGeometry.quads(MemoryUtil.memAddress(source), 1);
            assertEquals(16, canonical.remaining());
            assertEquals(3 | (5 << 10) | (7 << 20), canonical.getInt(0));
            assertEquals(16 | (8 << 16), canonical.getInt(4));
            assertEquals(123 | (5 << 24), canonical.getInt(8));
            assertEquals(67, canonical.getInt(12));
        } finally {
            MemoryUtil.memFree(source);
            if (canonical != null) MemoryUtil.memFree(canonical);
        }
    }

    @Test
    void modelProjectionKeepsSourceCropDepthTintAtlasAndMedium() {
        int[] source = new int[16];
        int face = 0xa4c2 | (31 << 16) | (1 << 22) | (2 << 24);
        java.util.Arrays.fill(source, 0, 6, face);
        source[6] = 7;
        source[7] = 47;
        source[9] = 9;
        source[10] = Float.floatToRawIntBits(.75f);
        source[11] = 1;
        source[12] = 19;
        source[13] = 0x56585054;
        source[14] = 1;
        source[15] = 3;
        ByteBuffer out = VoxyGeometry.models(source, 1);
        try {
            assertEquals(224, out.remaining());
            assertEquals(2 / 16f - .00005f, out.getFloat(0));
            assertEquals(31 / 64f, out.getFloat(16));
            assertEquals(1 - 31 / 64f, out.getFloat(48));
            assertEquals(9, out.getInt(192));
            assertEquals(47, out.getInt(196));
            assertEquals(7, out.getInt(200));
            assertEquals(.75f, out.getFloat(204));
            assertEquals(1, out.getInt(208));
            assertEquals(3, out.getInt(212));
        } finally {
            MemoryUtil.memFree(out);
        }
    }

    @Test
    void inheritedFluidDependencyIsNotAnOwnBiomeColourIndex() {
        // ModelFactory uses -1 for untinted models, even when their fluid is biome coloured.
        assertTint(0, -1, false);
        assertTint(2, -1, false);
        assertTint(6, -1, false);
        assertTint(1, 0xff619961, false);
        assertTint(1, -1, false);
        assertTint(3, 0, true);
        assertTint(7, 47, true);
    }

    private static void assertTint(int sourceFlags, int tint, boolean biome) {
        int[] source = new int[16];
        source[0] = 0x02000000;
        source[6] = sourceFlags;
        source[7] = tint;
        source[13] = 0x56585054;
        ByteBuffer out = VoxyGeometry.models(source, 1);
        try {
            assertEquals(tint, out.getInt(196));
            assertEquals(biome, (out.getInt(200) & 1) != 0);
            assertEquals((sourceFlags & 4) != 0, (out.getInt(200) & 2) != 0);
            assertEquals(2, (out.getInt(28) >>> 3) & 3);
            // This is the Native bounds contract: only flagged indices access the palette.
            assertTrue(!biome || Integer.toUnsignedLong(out.getInt(196)) < 48);
        } finally {
            MemoryUtil.memFree(out);
        }
    }
}
