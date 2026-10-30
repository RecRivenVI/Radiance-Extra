package com.radiance.extra.mixins.compatibility.sable;

import com.radiance.extra.compatibility.sable.RadianceSableStagingBuffer;
import dev.ryanhcode.sable.sublevel.render.staging.StagingBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Pseudo
@Mixin(value = StagingBuffer.class, remap = false)
public abstract class SableStagingBufferMixins {

    @Inject(
            method = "create(J)Ldev/ryanhcode/sable/sublevel/render/staging/StagingBuffer;",
            at = @At("HEAD"),
            cancellable = true,
            remap = false)
    private static void radiance$create(long size, CallbackInfoReturnable<StagingBuffer> cir) {
        cir.setReturnValue(new RadianceSableStagingBuffer(size));
    }
}
