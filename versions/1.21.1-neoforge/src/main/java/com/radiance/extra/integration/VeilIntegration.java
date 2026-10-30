package com.radiance.extra.integration;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.radiance.api.render.SourceIntegration;
import com.radiance.extra.compatibility.veil.VeilAdapter;
import net.minecraft.client.renderer.ShaderInstance;

public final class VeilIntegration implements SourceIntegration.Raster {
    public String id() {
        return "radiance_extra:veil";
    }

    public boolean skipState(Object state) {
        return VeilAdapter.shouldSkipRenderState(state);
    }

    public boolean supportsStaticState(net.minecraft.client.renderer.RenderType layer) {
        var access = foundry.veil.api.client.render.rendertype.VeilRenderType.getShards(layer);
        if (access == null) return true;
        var states = access.states();
        if (states.size() < 13) return false;
        // The reviewed append-only extension leaves the thirteen source shards intact.
        // Dynamic buffers are already explicitly excluded by the existing Vulkan adapter.
        for (int i = 13; i < states.size(); i++)
            if (!VeilAdapter.shouldSkipRenderState(states.get(i))) return false;
        return true;
    }

    public void validateDraw(
            ShaderInstance shader, VertexFormat f, VertexFormat.Mode mode, int v, int i) {
        VeilAdapter.requireSpringRasterDraw(shader, f, mode, v, i);
    }
}
