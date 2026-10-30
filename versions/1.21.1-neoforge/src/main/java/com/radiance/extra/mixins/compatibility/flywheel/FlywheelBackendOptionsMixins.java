package com.radiance.extra.mixins.compatibility.flywheel;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.radiance.extra.compatibility.flywheel.RadianceFlywheelBackend;
import dev.engine_room.flywheel.api.registry.IdRegistry;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** Command suggestions advertise only a backend that can actually run under Radiance. */
@Pseudo
@Mixin(targets = "dev.engine_room.flywheel.impl.BackendArgument", remap = false)
public abstract class FlywheelBackendOptionsMixins {
    @WrapOperation(
            method = "listSuggestions",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Ldev/engine_room/flywheel/api/registry/IdRegistry;getAllIds()Ljava/util/Set;"))
    private Set<ResourceLocation> radiance$available(
            IdRegistry<?> registry, Operation<Set<ResourceLocation>> original) {
        return RadianceFlywheelBackend.nativeSupported()
                ? Set.of(RadianceFlywheelBackend.ID)
                : original.call(registry);
    }
}
