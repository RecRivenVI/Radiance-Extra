package com.radiance.extra.compatibility.aeronautics;

import net.minecraft.client.renderer.RenderType;

/** Does not link optional Aeronautics classes when only Sable/Veil are installed. */
public final class LevititeLayers {
    private LevititeLayers() {}

    public static boolean owns(RenderType layer) {
        return layer.name.equals("aeronautics:levitite")
                || layer.name.equals("aeronautics:levitite_ghosts");
    }
}
