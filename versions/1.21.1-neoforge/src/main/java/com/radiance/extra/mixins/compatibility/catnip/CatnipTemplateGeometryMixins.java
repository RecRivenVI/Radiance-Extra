package com.radiance.extra.mixins.compatibility.catnip;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.api.render.SourceHooks;
import com.radiance.api.render.WorldScene;
import com.radiance.client.proxy.vulkan.FramebufferProxy;
import com.radiance.client.render.RasterPreviewScope;
import com.radiance.client.vertex.PBRVertexConsumer;
import com.radiance.extra.compatibility.catnip.CatnipTemplateGeometry;
import com.radiance.render.contract.DrawStateSnapshot.UvTransform;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SuperByteBuffer;
import net.createmod.catnip.render.TemplateMesh;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(targets = "net.createmod.catnip.render.ShadeSeparatingSuperByteBuffer", remap = false)
public abstract class CatnipTemplateGeometryMixins {
    @Shadow @Final private TemplateMesh template;
    @Shadow @Final private PoseStack transforms;
    @Shadow private float r, g, b, a;
    @Shadow private boolean hasCustomOverlay, disableDiffuse, useLevelLight;
    @Shadow private int overlay;
    @Unique private UvTransform radiance$uv = UvTransform.IDENTITY;
    @Unique private final PoseStack radiance$pose = new PoseStack();

    @Inject(method = "reset", at = @At("RETURN"))
    private void radiance$reset(CallbackInfoReturnable<SuperByteBuffer> cir) {
        radiance$uv = UvTransform.IDENTITY;
    }

    @Inject(method = "shiftUV", at = @At("RETURN"))
    private void radiance$shift(
            SpriteShiftEntry entry, CallbackInfoReturnable<SuperByteBuffer> cir) {
        radiance$uv = CatnipTemplateGeometry.shift(entry, 0, 0, 1);
    }

    @Inject(
            method =
                    "shiftUVScrolling(Lnet/createmod/catnip/render/SpriteShiftEntry;FF)Lnet/createmod/catnip/render/SuperByteBuffer;",
            at = @At("RETURN"))
    private void radiance$scroll(
            SpriteShiftEntry entry, float u, float v, CallbackInfoReturnable<SuperByteBuffer> cir) {
        var from = entry.getOriginal();
        var to = entry.getTarget();
        radiance$uv =
                new UvTransform(
                        1, 0, to.getU0() - from.getU0() + u, 0, 1, to.getV0() - from.getV0() + v);
    }

    @Inject(method = "shiftUVtoSheet", at = @At("RETURN"))
    private void radiance$sheet(
            SpriteShiftEntry entry,
            float u,
            float v,
            int size,
            CallbackInfoReturnable<SuperByteBuffer> cir) {
        var to = entry.getTarget();
        radiance$uv =
                CatnipTemplateGeometry.shift(
                        entry,
                        u * (to.getU1() - to.getU0()),
                        v * (to.getV1() - to.getV0()),
                        1f / size);
    }

    @WrapMethod(method = "renderInto")
    private void radiance$template(
            PoseStack input, VertexConsumer consumer, Operation<Void> original) {
        if (template.isEmpty() || RasterPreviewScope.active()) {
            original.call(input, consumer);
            return;
        }
        VertexConsumer output = consumer;
        // Direct PT output needs no source dispatch. Resolve only known source lighting wrappers;
        // other decorators and raster previews retain their own behavior.
        if (!(output instanceof PBRVertexConsumer)) output = SourceHooks.bulkConsumer(output, true);
        if (!(output instanceof PBRVertexConsumer)) {
            var stage = WorldScene.currentStage();
            // A world callback can also render a preview to its own FBO. Only the actual
            // main-world consumer wrappers may inherit PT's received-light policy.
            if (!WorldScene.capturingWorld()
                    || FramebufferProxy.boundFramebuffer(FramebufferProxy.DRAW_FRAMEBUFFER) != 0
                    || (stage != null
                            && stage.targetKind() != WorldScene.TargetKind.DEFAULT_WORLD)) {
                original.call(input, consumer);
                return;
            }
        }
        var pose = radiance$pose;
        pose.last().pose().set(input.last().pose()).mul(transforms.last().pose());
        pose.last().normal().set(input.last().normal()).mul(transforms.last().normal());
        if (CatnipTemplateGeometry.submit(
                template,
                pose.last(),
                output,
                r,
                g,
                b,
                a,
                hasCustomOverlay,
                overlay,
                radiance$uv)) {
            ((SuperByteBuffer) (Object) this).reset();
        } else {
            // A PT fallback must not bake raster diffuse or sample received level lighting.
            disableDiffuse = true;
            useLevelLight = false;
            try {
                original.call(input, output);
            } finally {
                ((SuperByteBuffer) (Object) this).reset();
            }
        }
    }
}
