package com.radiance.extra.audit.mixin;

import com.radiance.extra.compatibility.aeronautics.LevititeWorldAdapter;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Apply benchmark inputs after client ticks and immediately before their actual consumer. */
@Mixin(value = LevititeWorldAdapter.class, remap = false)
public abstract class LevititeStressMixin {
    @Inject(method = "collect", at = @At("HEAD"))
    private static void audit$effectInputs(ClientLevel level, float partial, CallbackInfo ci) {
        com.radiance.extra.audit.LevititeStressProbe.prepareFrame(level);
    }

    @Inject(method = "collect", at = @At("RETURN"))
    private static void audit$effectCost(ClientLevel level, float partial, CallbackInfo ci) {
        com.radiance.extra.audit.LevititeStressProbe.finishFrame();
    }
}
