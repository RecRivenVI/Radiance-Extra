package com.radiance.extra.audit.mixin;

import com.radiance.client.proxy.vulkan.ShaderProxy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bounded, opt-in shader names at the real UI submission boundary. */
@Mixin(value = ShaderProxy.class, remap = false)
public abstract class LevititePonderDrawMixin {
    @Inject(
            method =
                    "draw(Lcom/radiance/client/proxy/vulkan/BufferProxy$VertexIndexBufferHandle;IIIJI)V",
            at = @At("HEAD"))
    private static void audit$ponderDraw(CallbackInfo ci) {
        com.radiance.extra.audit.LevititePonderProbe.recordDraw();
    }
}
