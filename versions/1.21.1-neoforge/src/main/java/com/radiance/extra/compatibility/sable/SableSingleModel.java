package com.radiance.extra.compatibility.sable;

import com.mojang.blaze3d.vertex.PoseStack;
import com.radiance.api.render.*;
import dev.ryanhcode.sable.platform.SableSubLevelRenderPlatform;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.SingleBlockSubLevelWrapper;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3d;

/** Local source definition; parent motion never re-expands a stable baked model. */
final class SableSingleModel implements AutoCloseable {
    private record Key(
            BlockState state,
            BakedModel model,
            Object modelData,
            List<RenderType> layers,
            List<Integer> colours,
            List<BakedQuad> shape,
            Object offset) {}

    private static final Map<Key, Resource> SHARED = new HashMap<>();
    private BakedModel shapeModel;
    private BlockState shapeState;
    private Object shapeData;
    private long shapeSeed;
    private List<RenderType> shapeLayers;
    private List<BakedQuad> shape = List.of();
    private int[] tintIndices = new int[0];
    private BakedModel verifiedModel;
    private boolean immutable;
    private static long builds, sharedHits, dynamicBuilds, selections;

    private static final class Resource {
        final LocalModel model;
        int references;

        Resource(LocalModel model) {
            this.model = model;
        }
    }

    private Key key;
    private Resource resource;
    private LocalModel.Instance instance;
    private final long identity = RenderResources.geometryIdentity();
    private final Vector3d position = new Vector3d();

    void prepare(ClientSubLevel source, Vector3d origin) {
        var bounds = source.getPlot().getBoundingBox();
        if (bounds == null) return;
        var pos = new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ());
        var level = source.getLevel();
        var state = level.getBlockState(pos);
        if (state.getRenderShape() != RenderShape.MODEL) {
            release();
            return;
        }
        var mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        var wrapper = new SingleBlockSubLevelWrapper();
        var render = source.renderPose();
        wrapper.setup(
                level,
                render.position().x(),
                render.position().y(),
                render.position().z(),
                pos,
                state);
        try {
            long seed = state.getSeed(pos);
            var random = RandomSource.create(seed);
            var platform = SableSubLevelRenderPlatform.INSTANCE;
            var requestedLayers = platform.getRenderLayers(wrapper, model, state, pos, random);
            var layers =
                    RenderType.chunkBufferLayers().stream()
                            .filter(requestedLayers::contains)
                            .toList();
            if (verifiedModel != model) {
                verifiedModel = model;
                immutable = RenderResources.immutableBlockModel(model);
            }
            boolean stable = immutable;
            var data = level.getModelData(pos);
            var colours = new ArrayList<Integer>();
            if (stable) {
                if (shapeModel != model
                        || shapeState != state
                        || shapeData != data
                        || shapeSeed != seed
                        || !layers.equals(shapeLayers)) {
                    var quads = new ArrayList<BakedQuad>();
                    for (var layer : layers)
                        for (int face = -1; face < 6; face++)
                            quads.addAll(
                                    model.getQuads(
                                            state,
                                            face < 0 ? null : Direction.values()[face],
                                            RandomSource.create(seed),
                                            data,
                                            layer));
                    shape = List.copyOf(quads);
                    tintIndices =
                            quads.stream()
                                    .filter(BakedQuad::isTinted)
                                    .mapToInt(BakedQuad::getTintIndex)
                                    .distinct()
                                    .sorted()
                                    .toArray();
                    shapeModel = model;
                    shapeState = state;
                    shapeData = data;
                    shapeSeed = seed;
                    shapeLayers = layers;
                }
                for (int index : tintIndices) {
                    colours.add(index);
                    colours.add(mc.getBlockColors().getColor(state, wrapper, pos, index));
                    colours.add(
                            Float.floatToRawIntBits(
                                    RenderResources.blockTintEmission(state, wrapper, pos, index)));
                }
            }
            var next =
                    new Key(
                            state,
                            model,
                            data,
                            layers,
                            List.copyOf(colours),
                            shape,
                            state.getOffset(wrapper, pos));
            position.set(pos.getX() - origin.x, pos.getY() - origin.y, pos.getZ() - origin.z);
            if (stable && resource != null && next.equals(key)) return;
            release();
            key = stable ? next : null;
            resource = stable ? SHARED.get(key) : null;
            if (resource != null) sharedHits++;
            else {
                resource =
                        new Resource(
                                new LocalModel(
                                        buffers -> {
                                            if (state.getRenderShape() != RenderShape.MODEL) return;
                                            for (var layer : layers) {
                                                var layerRandom = RandomSource.create(seed);
                                                // Sable samples the layer list before tessellation,
                                                // using the same RNG. Custom models may consume it.
                                                if (!platform.getRenderLayers(
                                                                wrapper,
                                                                model,
                                                                state,
                                                                pos,
                                                                layerRandom)
                                                        .contains(layer)) continue;
                                                platform.tesselateBlock(
                                                        wrapper,
                                                        model,
                                                        state,
                                                        pos,
                                                        new PoseStack(),
                                                        buffers.getBuffer(layer),
                                                        layerRandom,
                                                        seed,
                                                        OverlayTexture.NO_OVERLAY,
                                                        layer);
                                            }
                                        }));
                builds++;
                if (stable) SHARED.put(key, resource);
                else dynamicBuilds++;
            }
            resource.references++;
            instance = new LocalModel.Instance();
        } finally {
            wrapper.clear();
        }
    }

    void draw(RenderSpace space) {
        if (resource != null) {
            resource.model.draw(instance, identity, space, position);
            selections++;
        }
    }

    private void release() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
        if (resource != null && --resource.references == 0) {
            if (key != null) SHARED.remove(key, resource);
            resource.model.close();
        }
        resource = null;
        key = null;
    }

    static long[] counters() {
        return new long[] {builds, sharedHits, dynamicBuilds, selections, SHARED.size()};
    }

    @Override
    public void close() {
        release();
    }
}
