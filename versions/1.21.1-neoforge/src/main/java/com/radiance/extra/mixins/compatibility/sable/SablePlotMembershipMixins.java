package com.radiance.extra.mixins.compatibility.sable;

import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = LevelPlot.class, remap = false)
public abstract class SablePlotMembershipMixins {
    @Inject(
            method = {"addChunkHolder", "onBlockChange"},
            at = @At("RETURN"))
    private void radiance$contentsChanged(CallbackInfo ci) {
        SableSceneRegistry.plotChanged(this);
    }
}
