package com.radiance.extra.audit;

import com.radiance.extra.compatibility.voxy.VoxyPtBridge;
import java.nio.ByteBuffer;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/** Reads real source rows only at functional checkpoints; no product diagnostics API. */
public final class VoxyTintAudit {
    private VoxyTintAudit() {}

    public static Map<String, Integer> capture() {
        try {
            Object session = field(VoxyPtBridge.class, "session", null);
            Object models = field(session.getClass(), "models", session);
            return inspect(
                    (int[]) field(models.getClass(), "records", models),
                    (int) field(models.getClass(), "count", models),
                    ((int[]) field(models.getClass(), "colours", models)).length);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Voxy source tint audit", e);
        }
    }

    static Map<String, Integer> inspect(int[] records, int count, int paletteSize) {
        int inherited = 0, constant = 0, biome = 0;
        ByteBuffer translated = translate(records, count);
        try {
            for (int i = 0; i < count; i++) {
                int at = i * 16;
                if (records[at + 13] != 0x56585054) continue;
                int flags = records[at + 6];
                boolean ownBiome = (flags & 3) == 3;
                int target = i * com.radiance.render.contract.RenderAbi.LOD_MODEL_BYTES;
                if (((translated.getInt(target + 200) & 1) != 0) != ownBiome
                        || translated.getInt(target + 196) != records[at + 7])
                    throw new IllegalStateException("Voxy tint translation changed source meaning");
                if (ownBiome) {
                    if (Integer.toUnsignedLong(records[at + 7]) >= paletteSize)
                        throw new IllegalStateException("Voxy own biome tint has no palette row");
                    biome++;
                } else if ((flags & 3) == 2) {
                    if (records[at + 7] != -1)
                        throw new IllegalStateException(
                                "Untinted Voxy fluid host lost white colour");
                    inherited++;
                } else if ((flags & 3) == 1) constant++;
            }
        } finally {
            MemoryUtil.memFree(translated);
        }
        if (inherited == 0 || constant == 0 || biome == 0)
            throw new IllegalStateException(
                    "Voxy fixture did not ingest all tint classes: "
                            + inherited
                            + "/"
                            + constant
                            + "/"
                            + biome);
        return Map.of(
                "untintedFluidHosts", inherited,
                "constantTintModels", constant,
                "ownBiomeTintModels", biome,
                "paletteColours", paletteSize);
    }

    private static ByteBuffer translate(int[] records, int count) {
        try {
            var method =
                    Class.forName("com.radiance.extra.compatibility.voxy.VoxyGeometry")
                            .getDeclaredMethod("models", int[].class, int.class);
            method.setAccessible(true);
            return (ByteBuffer) method.invoke(null, records, count);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Voxy translation audit", e);
        }
    }

    private static Object field(Class<?> owner, String name, Object instance)
            throws ReflectiveOperationException {
        var field = owner.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(instance);
    }
}
