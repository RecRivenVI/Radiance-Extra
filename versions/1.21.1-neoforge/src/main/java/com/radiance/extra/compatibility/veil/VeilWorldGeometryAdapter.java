package com.radiance.extra.compatibility.veil;

import com.radiance.client.render.SectionRasterStorage;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/** Supplies Radiance-owned section geometry to Veil's extra world-layer draw. */
public final class VeilWorldGeometryAdapter {
    private VeilWorldGeometryAdapter() {}

    public static boolean translatedWorldLayer(net.minecraft.client.renderer.RenderType layer) {
        return com.radiance.api.render.WorldScene.capturingWorld()
                && com.radiance.client.proxy.vulkan.FramebufferProxy.boundFramebuffer(
                                com.radiance.client.proxy.vulkan.FramebufferProxy.DRAW_FRAMEBUFFER)
                        == 0
                && com.radiance.extra.compatibility.aeronautics.LevititeLayers.owns(layer);
    }

    public static void withUploadedSections(
            ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections,
            double cameraX,
            double cameraY,
            double cameraZ,
            Runnable draw) {
        SectionRasterStorage.drain();
        var previous = new ObjectArrayList<>(visibleSections);
        try {
            visibleSections.clear();
            visibleSections.addAll(SectionRasterStorage.worldSections());
            visibleSections.sort(
                    java.util.Comparator.comparingDouble(
                            section ->
                                    section.getBoundingBox()
                                            .getCenter()
                                            .distanceToSqr(cameraX, cameraY, cameraZ)));
            draw.run();
        } finally {
            visibleSections.clear();
            visibleSections.addAll(previous);
        }
    }
}
