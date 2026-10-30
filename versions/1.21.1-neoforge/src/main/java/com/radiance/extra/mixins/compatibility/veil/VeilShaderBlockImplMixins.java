package com.radiance.extra.mixins.compatibility.veil;

import com.radiance.extra.compatibility.veil.VeilBlockState;
import com.radiance.extra.compatibility.veil.VeilShaderBlockData;
import foundry.veil.impl.client.render.shader.block.ShaderBlockImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = ShaderBlockImpl.class, remap = false)
public abstract class VeilShaderBlockImplMixins implements VeilBlockState {
    @Accessor("dirty")
    public abstract boolean radiance$isDirty();

    @Accessor("dirty")
    public abstract void radiance$setDirty(boolean dirty);

    @Inject(method = "free", at = @At("HEAD"), remap = false)
    private void radiance$freeSerialization(CallbackInfo ci) {
        if ((Object) this instanceof VeilShaderBlockData data) data.radiance$releaseBlock();
    }
}
