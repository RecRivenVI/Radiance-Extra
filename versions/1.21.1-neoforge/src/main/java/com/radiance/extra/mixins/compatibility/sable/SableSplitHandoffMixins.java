package com.radiance.extra.mixins.compatibility.sable;

import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.ClientSableInterpolationState;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ClientSubLevel.class, remap = false)
public abstract class SableSplitHandoffMixins {
    @Inject(method = "wasSplitFrom", at = @At("TAIL"))
    private void radiance$split(
            ClientSableInterpolationState state,
            ClientSubLevel parent,
            Pose3dc pose,
            CallbackInfo ci) {
        SableSceneRegistry.split((ClientSubLevel) (Object) this, parent);
    }
}
