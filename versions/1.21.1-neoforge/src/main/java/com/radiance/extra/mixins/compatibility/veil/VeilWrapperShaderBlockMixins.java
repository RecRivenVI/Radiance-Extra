package com.radiance.extra.mixins.compatibility.veil;

import foundry.veil.impl.client.render.shader.block.WrapperShaderBlockImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = WrapperShaderBlockImpl.class, remap = false)
public abstract class VeilWrapperShaderBlockMixins {

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$skipOpaqueOpenGlBinding(int index, CallbackInfo ci) {
        // Retain source binding ownership. A draw requiring this opaque block is declined
        // at uniform capture, where it cannot leave a half-recorded GPU command.
        ci.cancel();
    }

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$unbindCpuBlock(int index, CallbackInfo ci) {
        com.radiance.extra.compatibility.veil.VeilShaderAdapter.unbindBlock(
                (foundry.veil.api.client.render.shader.block.ShaderBlock<?>) (Object) this);
        ci.cancel();
    }
}
