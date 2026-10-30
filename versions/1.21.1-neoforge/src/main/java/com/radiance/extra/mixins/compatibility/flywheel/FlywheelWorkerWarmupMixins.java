package com.radiance.extra.mixins.compatibility.flywheel;

import com.radiance.extra.compatibility.flywheel.FlywheelModelWarmup;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
        targets = "dev.engine_room.flywheel.impl.task.ParallelTaskExecutor$WorkerThread",
        remap = false)
public abstract class FlywheelWorkerWarmupMixins {
    @Inject(method = "run", at = @At("HEAD"))
    private void radiance$prepareBeforeVisualTasks(CallbackInfo ci) {
        FlywheelModelWarmup.prepareWorker();
    }
}
