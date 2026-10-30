package com.radiance.extra.mixins.compatibility.simulated;

import com.radiance.extra.compatibility.sable.SableSceneRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = "dev.simulated_team.simulated.content.blocks.merging_glue.MergingGlueBlockEntity",
        remap = false)
public abstract class SimulatedMergeHandoffMixins {
    @Shadow private BlockPos partnerPosition;
    @Unique private BlockPos radiance$publishedPartner;

    @Inject(
            method = {"read", "setPartnerPos", "tick"},
            at = @At("RETURN"))
    private void radiance$mergeDestination(CallbackInfo ci) {
        if (!java.util.Objects.equals(partnerPosition, radiance$publishedPartner)
                && SableSceneRegistry.mergeLink((BlockEntity) (Object) this, partnerPosition))
            radiance$publishedPartner = partnerPosition;
    }
}
