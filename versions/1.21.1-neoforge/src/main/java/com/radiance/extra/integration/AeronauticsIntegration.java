package com.radiance.extra.integration;

import com.radiance.api.render.SourceIntegration;
import com.radiance.extra.compatibility.aeronautics.LevititeWorldAdapter;
import net.minecraft.client.multiplayer.ClientLevel;

public final class AeronauticsIntegration
        implements SourceIntegration.World, SourceIntegration.Geometry {
    public String id() {
        return "radiance_extra:aeronautics";
    }

    public void blocks(ClientLevel level, float partialTick) {
        LevititeWorldAdapter.collect(level, partialTick);
    }

    public void resourcesInvalidated() {
        LevititeWorldAdapter.close();
    }

    public void worldClosed() {
        LevititeWorldAdapter.close();
    }

    public void sectionInvalidated(net.minecraft.core.BlockPos origin) {
        com.radiance.extra.compatibility.aeronautics.LevititeContacts.invalidate(origin);
    }

    public boolean retainOffscreenLayer(net.minecraft.client.renderer.RenderType layer) {
        return LevititeWorldAdapter.owns(layer);
    }

    public boolean sectionLayer(
            net.minecraft.world.level.block.state.BlockState state,
            net.minecraft.client.renderer.RenderType layer) {
        if (layer.name.equals("aeronautics:levitite_ghosts")) return false;
        return layer != net.minecraft.client.renderer.RenderType.solid()
                || !(state.is(dev.eriksonn.aeronautics.index.AeroBlocks.LEVITITE.get())
                        || state.is(
                                dev.eriksonn.aeronautics.index.AeroBlocks.PEARLESCENT_LEVITITE
                                        .get()));
    }
}
