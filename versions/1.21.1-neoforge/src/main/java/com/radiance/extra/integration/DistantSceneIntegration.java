package com.radiance.extra.integration;

import com.radiance.api.render.LodScene;
import com.radiance.api.render.SourceIntegration;
import com.radiance.client.texture.TextureTasks;
import com.radiance.extra.compatibility.voxy.*;

public final class DistantSceneIntegration
        implements SourceIntegration.World, SourceIntegration.Spatial, SourceIntegration.Textures {
    public String id() {
        return "radiance_extra:voxy";
    }

    public void frameStarted() {
        VoxyPtBridge.frame();
    }

    public float imagingDistance(float distance) {
        return VoxyPtBridge.active()
                ? Math.max(
                        distance,
                        me.cortex.voxy.client.config.VoxyConfig.CONFIG.sectionRenderDistance * 512
                                - 32)
                : distance;
    }

    public void resourcesInvalidated() {
        VoxyPtBridge.reload();
    }

    public void worldClosed() {
        VoxyPtBridge.close();
    }

    public void nearSectionEmpty(int slot, int x, int y, int z) {
        LodScene.nearEmpty(slot, x, y, z);
    }

    public void atlas(TextureTasks.Owner o, int w, int h) {
        VoxyAtlasSnapshot.begin(o, w, h);
    }

    public void upload(
            TextureTasks.Owner o,
            int level,
            long address,
            int sw,
            int sh,
            int channels,
            int sx,
            int sy,
            int x,
            int y,
            int w,
            int h) {
        VoxyAtlasSnapshot.upload(o, level, address, sw, sh, channels, sx, sy, x, y, w, h);
    }
}
