package com.radiance.extra.mixins.compatibility.flywheel;

import com.radiance.extra.compatibility.flywheel.RadianceFlywheelBackend;
import dev.engine_room.flywheel.api.backend.Backend;
import dev.engine_room.flywheel.impl.NeoForgeFlwConfig;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Actual selection and the persisted/displayed preference use the same available backend. */
@Pseudo
@Mixin(value = NeoForgeFlwConfig.class, remap = false)
public abstract class FlywheelConfigMixins {
    @Shadow @Final public NeoForgeFlwConfig.ClientConfig client;

    @Inject(method = "backend", at = @At("HEAD"), cancellable = true)
    private void radiance$backend(CallbackInfoReturnable<Backend> cir) {
        if (!RadianceFlywheelBackend.nativeSupported()) return;
        var id = RadianceFlywheelBackend.ID;
        if (!id.toString().equals(client.backend.get())) client.backend.set(id.toString());
        var backend = Backend.REGISTRY.get(id);
        if (backend == null)
            throw new IllegalStateException("Radiance instance backend is not registered");
        cir.setReturnValue(backend);
    }
}
