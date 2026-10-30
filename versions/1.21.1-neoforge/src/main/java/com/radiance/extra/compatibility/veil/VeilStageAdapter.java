package com.radiance.extra.compatibility.veil;

import com.radiance.api.render.WorldScene;
import com.radiance.client.proxy.vulkan.FramebufferProxy;
import com.radiance.extra.compatibility.simulated.SimulatedStaffCapture;
import foundry.veil.api.event.VeilRenderLevelStageEvent;

/** Owns Veil render-stage relocation across the native world/UI boundary. */
public final class VeilStageAdapter {
    private VeilStageAdapter() {}

    public static VeilRenderLevelStageEvent pathTraceStaff(VeilRenderLevelStageEvent original) {
        return (WorldGeometryStageListener)
                (stage,
                        renderer,
                        buffers,
                        matrices,
                        view,
                        projection,
                        tick,
                        delta,
                        camera,
                        frustum) -> {
                    if (WorldScene.currentStage() == null
                            || WorldScene.currentStage().targetKind()
                                    != WorldScene.TargetKind.DEFAULT_WORLD
                            || FramebufferProxy.boundFramebuffer(FramebufferProxy.DRAW_FRAMEBUFFER)
                                    != 0
                            || stage != VeilRenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
                        original.onRenderLevelStage(
                                stage,
                                renderer,
                                buffers,
                                matrices,
                                view,
                                projection,
                                tick,
                                delta,
                                camera,
                                frustum);
                        return;
                    }
                    try (var capture = new SimulatedStaffCapture()) {
                        original.onRenderLevelStage(
                                stage,
                                renderer,
                                capture.locks,
                                matrices,
                                view,
                                projection,
                                tick,
                                delta,
                                camera,
                                frustum);
                        capture.submit();
                    }
                };
    }

    public static void runFixedStage(Runnable callback) {
        try (var source =
                com.radiance.client.render.LineSources.enter(
                        com.radiance.extra.integration.ExtraLineSources.VEIL_EVENT)) {
            callback.run();
        }
    }

    public static VeilRenderLevelStageEvent wrap(VeilRenderLevelStageEvent listener) {
        if (listener instanceof WorldGeometryStageListener) return listener;
        return (stage,
                renderer,
                buffers,
                matrices,
                view,
                projection,
                tick,
                delta,
                camera,
                frustum) -> {
            // The original callback still runs once. Geometry keeps its producer stage;
            // only the separately registered post-process may execute after world fusion.
            try (var source =
                    com.radiance.client.render.LineSources.enter(
                            com.radiance.extra.integration.ExtraLineSources.VEIL_EVENT)) {
                listener.onRenderLevelStage(
                        stage,
                        renderer,
                        buffers,
                        matrices,
                        view,
                        projection,
                        tick,
                        delta,
                        camera,
                        frustum);
            }
        };
    }
}
