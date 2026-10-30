package com.radiance.extra.mixins.compatibility.sable;

import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import dev.ryanhcode.sable.sublevel.plot.ClientLevelPlot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientLevelPlot.class, remap = false)
public abstract class SableClientPlotMembershipMixins {
    @Inject(
            method = {"addChunkHolder", "onRemoveChunkHolder"},
            at = @At("RETURN"))
    private void radiance$membershipChanged(CallbackInfo ci) {
        SableSceneRegistry.plotChanged(this);
    }
}
