package com.radiance.extra.mixins.compatibility.voxy;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.radiance.extra.compatibility.voxy.VoxyCaptureLock;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.WorldUpdater;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = WorldUpdater.class, remap = false)
public abstract class VoxyWorldUpdaterMixins {
    @WrapMethod(method = "insertUpdate")
    private static void radiance$atomicUpdate(
            WorldEngine world, VoxelizedSection section, Operation<Void> original) {
        VoxyCaptureLock.LOCK.writeLock().lock();
        var previous = VoxyCaptureLock.beginUpdate();
        try {
            original.call(world, section);
        } finally {
            VoxyCaptureLock.endUpdate(previous);
            VoxyCaptureLock.LOCK.writeLock().unlock();
        }
    }

    @WrapMethod(method = "insertSectionLvlIntoWorld")
    private static long radiance$contentUpdate(
            VoxelizedSection source, WorldSection target, Operation<Long> original) {
        // Also protect direct users of Voxy's public insertion helper.
        VoxyCaptureLock.LOCK.writeLock().lock();
        try {
            VoxyCaptureLock.beforeInsert(source, target);
            return original.call(source, target);
        } finally {
            VoxyCaptureLock.LOCK.writeLock().unlock();
        }
    }
}
