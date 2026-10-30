package com.radiance.extra.compatibility.simulated;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.radiance.client.vertex.PBRVertexConsumer;

/** Physical-surface lowering of the original live spring producer's vertices. */
public final class SpringWorldLowering {
    private SpringWorldLowering() {}

    public static PBRVertexConsumer createConsumer(
            ByteBufferBuilder allocator, SpringDrawContract.Draw draw) {
        SpringShaderVerification.requireActive();
        // The source-owned spring template keeps local geometry; appearance and the
        // active RenderType still resolve the current stress tint, body image and sampler.
        return PBRVertexConsumer.dynamicMaterial(
                allocator, draw.renderType(), false, SpringDrawContract.WORLD_MATERIAL);
    }
}
