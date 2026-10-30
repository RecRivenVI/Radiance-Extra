package com.radiance.extra.mixins.compatibility.sable;

import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public abstract class SableSectionMembershipMixins {
    @Inject(
            method = {"resize", "rebuild"},
            at = @At("RETURN"))
    private void radiance$membersChanged(CallbackInfo ci) {
        SableSceneRegistry.sectionsChanged(
                ((VanillaChunkedSubLevelRenderData) (Object) this).getSubLevel());
    }
}
