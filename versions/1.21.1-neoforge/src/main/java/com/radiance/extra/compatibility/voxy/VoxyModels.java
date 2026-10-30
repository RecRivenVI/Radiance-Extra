package com.radiance.extra.compatibility.voxy;

import com.radiance.api.render.RenderResources;
import com.radiance.extra.mixins.compatibility.voxy.VoxyBiomeUploadAccess;
import com.radiance.extra.mixins.compatibility.voxy.VoxyModelFactoryAccess;
import com.radiance.extra.mixins.compatibility.voxy.VoxyModelUploadAccess;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelQueries;
import me.cortex.voxy.common.util.MemoryBuffer;
import org.lwjgl.system.MemoryUtil;

/** Source projection output only: no full model/PBR reconstruction and no GL ModelStore. */
final class VoxyModels implements AutoCloseable {
    private final com.radiance.api.render.LodScene scene;
    final ModelFactory factory;
    private final List<RenderResources.Texture> pages = new ArrayList<>();
    private int[] records = new int[16 * 256];
    private int count;
    private int[] colours = new int[0];
    private boolean dirty;
    private final long[] summary = new long[6];

    VoxyModels(com.radiance.api.render.LodScene scene, ModelFactory factory) {
        this.scene = scene;
        this.factory = factory;
    }

    private void patchColours(MemoryBuffer bytes, int first) {
        if (bytes == null || bytes.size == 0) return;
        if ((bytes.size & 3) != 0 || first < 0 || first + bytes.size / 4 > 8 * 1024 * 1024)
            throw new IllegalArgumentException("Voxy biome table exceeds upload budget");
        int end = Math.toIntExact(first + bytes.size / 4);
        if (colours.length < end) colours = Arrays.copyOf(colours, end);
        for (int i = first; i < end; i++)
            colours[i] = MemoryUtil.memGetInt(bytes.address + (i - first) * 4L);
    }

    void drain() {
        Object result;
        var queue = ((VoxyModelFactoryAccess) factory).radiance$uploads();
        while ((result = queue.poll()) != null) {
            if (result instanceof VoxyModelUploadAccess model) {
                try {
                    accept(model);
                } finally {
                    model.radiance$free();
                }
            } else if (result instanceof VoxyBiomeUploadAccess biome) {
                try {
                    patchColours(biome.radiance$colours(), 0);
                    MemoryBuffer pairs = biome.radiance$pairs();
                    if ((pairs.size & 7) != 0)
                        throw new IllegalArgumentException("Voxy biome pair length");
                    for (long p = 0; p < pairs.size; p += 8) {
                        long pair = MemoryUtil.memGetLong(pairs.address + p);
                        int id = (int) pair;
                        if (id < 0 || id >= count)
                            throw new IllegalArgumentException("Unknown Voxy biome model");
                        records[id * 16 + 7] = (int) (pair >>> 32);
                    }
                    dirty = true;
                } finally {
                    biome.radiance$free();
                }
            } else
                throw new IllegalStateException(
                        "Unsupported Voxy model result " + result.getClass().getName());
        }
    }

    private void accept(VoxyModelUploadAccess model) {
        int id = model.radiance$id();
        if (id < 0 || id >= 65536 || model.radiance$model().size != 64)
            throw new IllegalArgumentException("Voxy model ABI mismatch");
        if (records.length < (id + 1) * 16)
            records =
                    Arrays.copyOf(
                            records,
                            Math.min(65536 * 16, Math.max(records.length * 2, (id + 1) * 16)));
        count = Math.max(count, id + 1);
        for (int i = 0; i < 16; i++)
            records[id * 16 + i] = MemoryUtil.memGetInt(model.radiance$model().address + i * 4L);
        int page = id >>> 8;
        while (pages.size() <= page) {
            pages.add(
                    RenderResources.rgba8(
                            768,
                            512,
                            4,
                            RenderResources.Sampling.NEAREST,
                            RenderResources.Sampling.LINEAR));
        }
        var owner = pages.get(page);
        int tile = id & 255;
        MemoryBuffer source = model.radiance$texture();
        long offset = 0;
        int levels = model.radiance$hasMips() ? 4 : 1;
        for (int level = 0; level < levels; level++) {
            int w = 48 >> level, h = 32 >> level;
            int bytes = w * h * 4;
            if (offset + bytes > source.size)
                throw new IllegalArgumentException("Voxy model texture length");
            owner.upload(
                    level,
                    ((tile & 15) * 48) >> level,
                    ((tile >>> 4) * 32) >> level,
                    w,
                    h,
                    MemoryUtil.memByteBuffer(source.address + offset, bytes));
            offset += bytes;
        }
        records[id * 16 + 9] = owner.id();
        var material = VoxySourceMaterials.material(factory, id);
        records[id * 16 + 8] = material.alpha();
        records[id * 16 + 10] = Float.floatToRawIntBits(material.emission() / 15.0f);
        records[id * 16 + 11] = material.fluid();
        records[id * 16 + 12] = tile;
        records[id * 16 + 13] = 0x56585054;
        records[id * 16 + 14] =
                ModelQueries.isDoubleSided(factory.getModelMetadataFromClientId(id)) ? 1 : 0;
        records[id * 16 + 15] = model.radiance$hasMips() ? 3 : 0;
        if (model.radiance$biomeIndex() >= 0)
            patchColours(model.radiance$biome(), model.radiance$biomeIndex());
        dirty = true;
    }

    void publish() {
        if (!dirty || count == 0) return;
        ByteBuffer models = VoxyGeometry.models(records, count);
        ByteBuffer tints = MemoryUtil.memAlloc(Math.max(4, colours.length * 4));
        try {
            for (int i = 0; i < colours.length; i++) tints.putInt(i * 4, colours[i]);
            tints.limit(colours.length * 4);
            scene.models(models, tints.asIntBuffer());
            java.util.Arrays.fill(summary, 0);
            for (int i = 0; i < count; i++) {
                int at = i * 16;
                if (records[at + 13] != 0x56585054) continue;
                summary[0]++;
                if (records[at + 11] == 1) summary[1]++;
                if (records[at + 8] == com.radiance.render.contract.RenderAbi.ALPHA_TRANSMISSION)
                    summary[2]++;
                if (records[at + 8] == com.radiance.render.contract.RenderAbi.ALPHA_COVERAGE)
                    summary[3]++;
                if (VoxyGeometry.hasBiomeTint(records[at + 6])) summary[4]++;
                if (Float.intBitsToFloat(records[at + 10]) > 0) summary[5]++;
            }
            dirty = false;
        } finally {
            MemoryUtil.memFree(models);
            MemoryUtil.memFree(tints);
        }
    }

    long[] summary() {
        return summary;
    }

    @Override
    public void close() {
        VoxySourceMaterials.release(factory);
        factory.free();
        for (var page : pages) page.close();
        pages.clear();
    }
}
