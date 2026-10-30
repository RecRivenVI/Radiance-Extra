package com.radiance.extra.mixins.compatibility.voxy;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.radiance.extra.compatibility.voxy.VoxyCaptureLock;
import me.cortex.voxy.common.world.WorldSection;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = WorldSection.class, remap = false)
public abstract class VoxyWorldSectionMixins {
    @WrapMethod(method = "set")
    private long radiance$atomicSet(int x, int y, int z, long value, Operation<Long> original) {
        VoxyCaptureLock.LOCK.writeLock().lock();
        try {
            long old = original.call(x, y, z, value);
            if (((old ^ value) & VoxyCaptureLock.CONTENT_MASK) != 0)
                VoxyCaptureLock.contentChanged((WorldSection) (Object) this);
            return old;
        } finally {
            VoxyCaptureLock.LOCK.writeLock().unlock();
        }
    }
}
