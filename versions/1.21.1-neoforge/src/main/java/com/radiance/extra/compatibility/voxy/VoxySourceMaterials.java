package com.radiance.extra.compatibility.voxy;

import com.radiance.extra.mixins.compatibility.voxy.VoxyModelFactoryAccess;
import com.radiance.render.contract.RenderAbi;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelLayer;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;

/**
 * Retains only classification still present in the source proxy, never original PBR or animation.
 */
public final class VoxySourceMaterials {
    private static final Map<ModelFactory, Map<Integer, Material>> MATERIALS = new WeakHashMap<>();

    public record Material(int fluid, int alpha, int emission) {}

    public record ProjectionKey(Object projection, Material material, BlockState biomeSource) {}

    public static final class Bake {
        private final BlockState state;
        private final Material material;
        private boolean biomeDependent;

        public Bake(BlockState state, ModelLayer layer) {
            this.state = state;
            material =
                    classify(state, layer, VoxyModelFactoryAccess.radiance$sourceEmission(state));
        }

        public void biomeDependent(boolean value) {
            biomeDependent = value;
        }

        public Object key(Object projection) {
            // No source contract proves that different biome-dependent providers are equivalent.
            // Preserve their state identity; untinted and constant-tint projections still intern.
            return new ProjectionKey(projection, material, biomeDependent ? state : null);
        }

        public void publish(ModelFactory factory, int id) {
            record(factory, id, material);
        }
    }

    static Material classify(BlockState state, ModelLayer layer, int sourceEmission) {
        int fluid =
                state.getBlock() instanceof LiquidBlock
                        ? state.getFluidState().is(FluidTags.WATER)
                                ? 1
                                : state.getFluidState().is(FluidTags.LAVA) ? 2 : 0
                        : 0;
        return classify(
                fluid,
                state.is(Tags.Blocks.GLASS_BLOCKS) || state.is(Tags.Blocks.GLASS_PANES),
                layer,
                sourceEmission);
    }

    static Material classify(int fluid, boolean glass, ModelLayer layer, int sourceEmission) {
        int alpha =
                fluid == 1 || glass
                        ? RenderAbi.ALPHA_TRANSMISSION
                        : layer == ModelLayer.TRANSLUCENT && fluid != 2
                                ? RenderAbi.ALPHA_COVERAGE
                                : layer == ModelLayer.SOLID || fluid == 2
                                        ? RenderAbi.ALPHA_OPAQUE
                                        : RenderAbi.ALPHA_CUTOUT_LOW;
        return new Material(fluid, alpha, sourceEmission);
    }

    private VoxySourceMaterials() {}

    private static synchronized void record(ModelFactory factory, int id, Material material) {
        Material old =
                MATERIALS
                        .computeIfAbsent(factory, key -> new HashMap<>())
                        .putIfAbsent(id, material);
        if (old != null && !old.equals(material))
            throw new IllegalStateException("Voxy interned incompatible source materials");
    }

    public static synchronized Material material(ModelFactory factory, int id) {
        var values = MATERIALS.get(factory);
        Material result = values == null ? null : values.get(id);
        if (result == null) throw new IllegalStateException("Unclassified Voxy projection: " + id);
        return result;
    }

    public static synchronized void release(ModelFactory factory) {
        MATERIALS.remove(factory);
    }
}
