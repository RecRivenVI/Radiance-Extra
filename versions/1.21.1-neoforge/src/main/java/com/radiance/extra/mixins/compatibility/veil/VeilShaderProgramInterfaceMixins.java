package com.radiance.extra.mixins.compatibility.veil;

import com.radiance.extra.compatibility.veil.VeilShaderAdapter;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = ShaderProgram.class, remap = false)
public interface VeilShaderProgramInterfaceMixins {

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$bindVulkanProgram(CallbackInfo ci) {
        VeilShaderAdapter.bindProgram((ShaderProgram) this);
        ci.cancel();
    }

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true, remap = false)
    private static void radiance$unbindVulkanProgram(CallbackInfo ci) {
        VeilShaderAdapter.unbindProgram();
        ci.cancel();
    }

    @Inject(method = "setUniformBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$keepUniformBlockBinding(CharSequence name, int binding, CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(method = "setStorageBlock", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$rejectStorageBlockBinding(
            CharSequence name, int binding, CallbackInfo ci) {
        // Source broadcasts storage bindings to every program, including unrelated ones.
        // Programs that actually declare SSBOs are rejected by the GLSL translator.
        ci.cancel();
    }
}
