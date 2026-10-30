package com.radiance.extra.compatibility.voxy;

import com.radiance.api.render.LodScene;
import com.radiance.render.contract.RenderAbi;
import java.nio.*;
import org.lwjgl.system.MemoryUtil;

final class VoxyGeometry {
    private VoxyGeometry() {}

    static boolean hasBiomeTint(int sourceFlags) {
        // A waterlogged solid can inherit its fluid's biome dependency without its own tint.
        return (sourceFlags & 3) == 3;
    }

    static long key(long source) {
        return LodScene.node(
                (int) (source >>> 60),
                (int) (source << 36 >> 40),
                (int) (source << 4 >> 56),
                (int) (source << 12 >> 40));
    }

    static long[] keys(long[] source) {
        for (int i = 0; i < source.length; i++) source[i] = key(source[i]);
        return source;
    }

    static ByteBuffer quads(long address, int count) {
        ByteBuffer out = MemoryUtil.memAlloc(Math.multiplyExact(count, RenderAbi.LOD_QUAD_BYTES));
        for (int i = 0; i < count; i++) {
            long q = MemoryUtil.memGetLong(address + i * 8L);
            int x = (int) (q >>> 21) & 31, y = (int) (q >>> 16) & 31, z = (int) (q >>> 11) & 31;
            int width = ((int) (q >>> 3) & 15) + 1, height = ((int) (q >>> 7) & 15) + 1;
            int face = (int) q & 7, model = (int) (q >>> 26) & 65535, tint = (int) (q >>> 46) & 511;
            out.putInt(i * 16, x | (y << 10) | (z << 20))
                    .putInt(i * 16 + 4, width | (height << 16))
                    .putInt(i * 16 + 8, model | (face << 24))
                    .putInt(i * 16 + 12, tint);
        }
        return out;
    }

    static ByteBuffer models(int[] source, int count) {
        ByteBuffer out = MemoryUtil.memCalloc(Math.multiplyExact(count, RenderAbi.LOD_MODEL_BYTES));
        for (int m = 0; m < count; m++) {
            int at = m * RenderAbi.LOD_MODEL_BYTES, s = m * 16;
            if (source[s + 13] != 0x56585054) continue;
            int tile = source[s + 12];
            for (int face = 0; face < 6; face++) {
                int f = source[s + face], p = at + face * 32;
                if (f == -1) continue;
                float u = (f & 15) / 16f - .00005f, v = ((f >>> 8) & 15) / 16f - .00005f;
                float width = (((f >>> 4) & 15) + 1) / 16f - u,
                        height = (((f >>> 12) & 15) + 1) / 16f - v;
                int d = (f >>> 16) & 63;
                d += d == 63 ? 1 : 0;
                float depth = d / 64f;
                if ((face & 1) != 0) depth = 1 - depth;
                int flags =
                        1
                                | (((f >>> 22) & 1) << 1)
                                | (((f >>> 23) & 1) << 2)
                                | (((f >>> 24) & 3) << 3);
                out.putFloat(p, u)
                        .putFloat(p + 4, v)
                        .putFloat(p + 8, width)
                        .putFloat(p + 12, height)
                        .putFloat(p + 16, depth)
                        .putFloat(p + 20, ((tile & 15) * 3 + (face >>> 1)) / 48f)
                        .putFloat(p + 24, ((tile >>> 4) * 2 + (face & 1)) / 32f)
                        .putInt(p + 28, flags);
            }
            int flags =
                    (hasBiomeTint(source[s + 6]) ? 1 : 0)
                            | ((source[s + 6] & 4) != 0 ? 2 : 0)
                            | (source[s + 14] != 0 ? 4 : 0)
                            | (source[s + 8] << RenderAbi.LOD_ALPHA_SHIFT);
            out.putInt(at + 192, source[s + 9])
                    .putInt(at + 196, source[s + 7])
                    .putInt(at + 200, flags)
                    .putInt(at + 204, source[s + 10])
                    .putInt(at + 208, source[s + 11])
                    .putInt(at + 212, source[s + 15])
                    .putFloat(at + 216, 1 / 48f)
                    .putFloat(at + 220, 1 / 32f);
        }
        return out;
    }
}
