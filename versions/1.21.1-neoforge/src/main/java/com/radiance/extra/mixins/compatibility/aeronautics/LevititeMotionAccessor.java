package com.radiance.extra.mixins.compatibility.aeronautics;

import dev.eriksonn.aeronautics.content.blocks.levitite.LevititeShaderManager;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = LevititeShaderManager.class, remap = false)
public interface LevititeMotionAccessor {
    @Accessor("smoothedLinearVelocity")
    Vector3d radiance$linear();

    @Accessor("lastSmoothedLinearVelocity")
    Vector3d radiance$previousLinear();

    @Accessor("smoothedAngularVelocity")
    Vector3d radiance$angular();

    @Accessor("lastSmoothedAngularVelocity")
    Vector3d radiance$previousAngular();
}
