package com.radiance.extra.compatibility.sable;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.extra.mixins.compatibility.sable.SableSubLevelVertexConsumerAccessor;

public final class SableVertexConsumerCompat {

    private static final String SABLE_SUB_LEVEL_VERTEX_CONSUMER =
            "dev.ryanhcode.sable.render.dynamic_shade.SubLevelVertexConsumer";
    private static final String UNIVERSAL_MESH_EMITTER =
            "net.createmod.catnip.impl.client.render.model.UniversalMeshEmitter";
    private static final String FLYWHEEL_MESH_EMITTER =
            "dev.engine_room.flywheel.lib.model.baked.NeoforgeMeshEmitter";

    private SableVertexConsumerCompat() {}

    public static VertexConsumer bulkDataConsumer(VertexConsumer consumer, boolean pathTraced) {
        if (pathTraced) {
            // Source adapters can nest this lighting-only wrapper. Resolve the actual PT
            // consumer before selecting a template path, including off-frame model preparation.
            while (SABLE_SUB_LEVEL_VERTEX_CONSUMER.equals(consumer.getClass().getName())
                    && consumer instanceof SableSubLevelVertexConsumerAccessor accessor) {
                VertexConsumer delegate = accessor.radiance$getDelegate();
                if (delegate == null) break;
                consumer = delegate;
            }
            return consumer;
        }
        if (!SABLE_SUB_LEVEL_VERTEX_CONSUMER.equals(consumer.getClass().getName())) {
            return consumer;
        }

        if (!(consumer instanceof SableSubLevelVertexConsumerAccessor accessor)) {
            return consumer;
        }

        VertexConsumer delegate = accessor.radiance$getDelegate();
        if (delegate == null) return consumer;
        String emitter = delegate.getClass().getName();
        if (UNIVERSAL_MESH_EMITTER.equals(emitter) || FLYWHEEL_MESH_EMITTER.equals(emitter)) {
            // Both emitters require bulk quads. Reload can warm Flywheel from a raster overlay,
            // where Sable's wrapper would otherwise expand the quad through addVertex().
            return delegate;
        }
        return consumer;
    }
}
