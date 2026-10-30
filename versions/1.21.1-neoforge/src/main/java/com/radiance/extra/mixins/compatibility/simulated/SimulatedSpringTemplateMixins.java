package com.radiance.extra.mixins.compatibility.simulated;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.ref.LocalRef;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.extra.compatibility.simulated.SpringShapeCache;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlock;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.spring.SpringRenderer", remap = false)
public abstract class SimulatedSpringTemplateMixins {
    @Unique private final SpringShapeCache.Store radiance$springs = new SpringShapeCache.Store();
    @Unique private SpringShapeCache.Frame radiance$frame;

    @WrapMethod(
            method =
                    "renderSafe(Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V")
    private void radiance$scope(
            SpringBlockEntity be,
            float partial,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            int overlay,
            Operation<Void> original) {
        var previous = radiance$frame;
        radiance$frame = null;
        try {
            original.call(be, partial, poses, buffers, light, overlay);
            if (radiance$frame != null) radiance$frame.finish();
        } finally {
            radiance$frame = previous;
        }
    }

    @Inject(
            method =
                    "renderSafe(Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/simulated_team/simulated/content/blocks/spring/SpringRenderer;getStressColor(Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;FLorg/joml/Vector3d;Lorg/joml/Vector3dc;Lnet/minecraft/client/Minecraft;)I"))
    private void radiance$localEndpoints(
            SpringBlockEntity be,
            float partial,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            int overlay,
            CallbackInfo ci,
            @Local(name = "buffer") VertexConsumer out,
            @Local(name = "other") SpringBlockEntity other,
            @Local(name = "subLevel") ClientSubLevel space,
            @Local(name = "otherSubLevel") ClientSubLevel otherSpace,
            @Local(name = "otherCenter") Vector3d otherCenter,
            @Local(name = "normalB") Vector3d normal,
            @Local(name = "renderPose") LocalRef<Pose3dc> pose,
            @Local(name = "otherRenderPose") LocalRef<Pose3dc> otherPose) {
        if (space != otherSpace
                || !com.radiance.api.render.LocalGeometry.supported(
                        poses.last(), SpringShapeCache.consumer(out))) return;
        // Both endpoints share the same rigid space. Avoid an imprecise local->world->local
        // roundtrip.
        otherCenter.set(other.getCenter());
        var direction = other.getBlockState().getValue(SpringBlock.FACING);
        normal.set(direction.getStepX(), direction.getStepY(), direction.getStepZ());
        pose.set(null);
        otherPose.set(null);
    }

    @Inject(
            method =
                    "renderSafe(Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/simulated_team/simulated/content/blocks/spring/SpringRenderer;generateSpline(Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;D)Ljava/util/List;"),
            cancellable = true)
    private void radiance$shape(
            SpringBlockEntity be,
            float partial,
            PoseStack poses,
            MultiBufferSource buffers,
            int light,
            int overlay,
            CallbackInfo ci,
            @Local(name = "buffer") VertexConsumer out,
            @Local(name = "other") SpringBlockEntity other,
            @Local(name = "center") Vector3dc center,
            @Local(name = "otherCenter") Vector3d otherCenter,
            @Local(name = "normalB") Vector3d normal,
            @Local(name = "renderPose") Pose3dc pose,
            @Local(name = "otherRenderPose") Pose3dc otherPose,
            @Local(name = "color") int color) {
        var relative =
                otherPose == null ? new Quaterniond() : new Quaterniond(otherPose.orientation());
        if (pose != null) relative.premul(pose.orientation().conjugate(new Quaterniond()));
        var inputs =
                new SpringShapeCache.Inputs(
                        be.getBlockState(),
                        other.getBlockState(),
                        otherCenter.x - center.x(),
                        otherCenter.y - center.y(),
                        otherCenter.z - center.z(),
                        normal.x,
                        normal.y,
                        normal.z,
                        relative.x,
                        relative.y,
                        relative.z,
                        relative.w);
        var frame =
                radiance$springs.begin(
                        be,
                        inputs,
                        poses.last(),
                        out,
                        light,
                        color,
                        (float) (be.getRenderLength(partial) - .75));
        if (frame == null) return;
        if (frame.reused()) {
            poses.popPose();
            ci.cancel();
        } else radiance$frame = frame;
    }

    @ModifyArg(
            method =
                    "renderSafe(Ldev/simulated_team/simulated/content/blocks/spring/SpringBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/simulated_team/simulated/content/blocks/spring/SpringRenderer;renderSegment(Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;Lorg/joml/Vector3dc;ZFFIILcom/mojang/blaze3d/vertex/VertexConsumer;FF)V"),
            index = 12)
    private VertexConsumer radiance$record(VertexConsumer original) {
        return radiance$frame == null ? original : radiance$frame;
    }
}
