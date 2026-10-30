package com.radiance.extra.mixins.compatibility.veil;

import com.radiance.extra.compatibility.veil.VeilShaderAdapter;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.impl.client.render.pipeline.VeilShaderBlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = VeilShaderBlockState.class, remap = false)
public abstract class VeilShaderBlockStateMixins {

    @Redirect(
            method = {"bind", "unbind"},
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lfoundry/veil/api/client/render/shader/ShaderManager;setGlobal(Ljava/util/function/Consumer;)V"),
            remap = false)
    private void radiance$omitOpenGlBindingBroadcast(
            foundry.veil.api.client.render.shader.ShaderManager manager,
            java.util.function.Consumer<foundry.veil.api.client.render.shader.program.ShaderProgram>
                    assignment) {
        // The Vulkan wrapper flattens named block data into each draw's uniforms;
        // broadcasting GL binding indices to every program has no consumer. Keep
        // Veil's slot allocation, eviction, packed/global ownership and callbacks.
    }

    @Inject(
            method =
                    "bind(Ljava/lang/CharSequence;Lfoundry/veil/api/client/render/shader/block/ShaderBlock;)V",
            at = @At("RETURN"),
            remap = false)
    private void radiance$captureBlock(CharSequence name, ShaderBlock<?> block, CallbackInfo ci) {
        VeilShaderAdapter.bindBlock(name, block);
    }

    @Inject(method = "unbind", at = @At("HEAD"), remap = false)
    private void radiance$releaseBlock(ShaderBlock<?> block, CallbackInfo ci) {
        VeilShaderAdapter.unbindBlock(block);
    }
}
