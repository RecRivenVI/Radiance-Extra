package com.radiance.extra.compatibility.simulated;

import com.radiance.api.render.RenderPlatform;
import net.neoforged.fml.ModList;

/** Source-owned world effect, independent of the post-PT raster bridge. */
public final class SimulatedWorldEffects {
    private static EndSeaWorldSurface surface;
    private static Object level;
    private static long epoch;

    private SimulatedWorldEffects() {}

    public static void updateSectionColour(
            com.radiance.api.render.RenderSpace space,
            net.minecraft.client.multiplayer.ClientLevel world) {
        if (!ModList.get().isLoaded("simulated")) return;
        var physics = dev.simulated_team.simulated.content.end_sea.EndSeaPhysicsData.of(world);
        if (physics == null) space.clearSectionColourGradient();
        else
            space.sectionColourGradient(
                    0, -1 / 30f, 0, (float) (physics.startY() / 30), .172f, .156f, .218f);
    }

    public static void collectAfterMask() {
        if (!ModList.get().isLoaded("simulated")) return;
        var minecraft = net.minecraft.client.Minecraft.getInstance();
        if (minecraft.level != level || epoch != RenderPlatform.resourceEpoch()) close();
        if (minecraft.level == null
                || dev.simulated_team.simulated.content.end_sea.EndSeaPhysicsData.of(
                                minecraft.level)
                        == null) {
            close();
            return;
        }
        if (surface == null) {
            surface = new EndSeaWorldSurface();
            level = minecraft.level;
            epoch = RenderPlatform.resourceEpoch();
        }
        surface.collect();
    }

    public static void close() {
        SimulatedDiagramCompatibility.closeWorldMask();
        if (surface != null) surface.close();
        surface = null;
        level = null;
    }
}
