package com.radiance.extra.integration;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.api.render.SourceIntegration;
import com.radiance.extra.compatibility.simulated.*;
import net.minecraft.client.renderer.RenderType;

public final class SimulatedIntegration
        implements SourceIntegration.World, SourceIntegration.Geometry, SourceIntegration.Raster {
    public void worldClosed() {
        SimulatedWorldEffects.close();
    }

    public String id() {
        return "radiance_extra:simulated";
    }

    public void resourcesInvalidated() {
        DiagramSectionMeshes.invalidateResources();
        SimulatedWorldEffects.close();
    }

    public VertexConsumer quadConsumer(ByteBufferBuilder a, RenderType t) {
        return prioritySurface(t) || supportsColourState(t)
                ? SimulatedVertexCompatibility.createQuadConsumer(a, t)
                : null;
    }

    public boolean prioritySurface(RenderType t) {
        return SimulatedVertexCompatibility.isLockLayer(t);
    }

    public boolean supportsColourState(RenderType t) {
        return SpringDrawContract.from(t) != null;
    }

    public boolean cameraRelative(RenderType t) {
        return SimulatedEntityCompatibility.isCameraRelativeLayer(t);
    }

    public Integer cameraRelativeIdentity(Object o) {
        return SimulatedEntityCompatibility.cameraRelativeIdentity(o);
    }

    public int lightmap() {
        return DiagramLightmaps.currentTexture();
    }

    public boolean compilingOffscreenSection() {
        return DiagramSectionMeshes.compiling();
    }
}
