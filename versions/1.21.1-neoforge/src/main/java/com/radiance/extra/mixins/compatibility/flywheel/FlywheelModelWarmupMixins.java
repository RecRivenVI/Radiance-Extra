package com.radiance.extra.mixins.compatibility.flywheel;

import com.radiance.extra.compatibility.flywheel.FlywheelModelWarmup;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RendererReloadCache.class, remap = false)
public abstract class FlywheelModelWarmupMixins {
    @Inject(method = "onReloadLevelRenderer", at = @At("TAIL"))
    private static void radiance$prepareAfterCacheClear(CallbackInfo ci) {
        FlywheelModelWarmup.prepareModels();
    }
}
