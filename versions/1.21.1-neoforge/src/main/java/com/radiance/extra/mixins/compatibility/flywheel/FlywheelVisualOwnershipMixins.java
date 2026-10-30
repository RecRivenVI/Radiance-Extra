package com.radiance.extra.mixins.compatibility.flywheel;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.radiance.extra.compatibility.flywheel.FlywheelHandoffs;
import dev.engine_room.flywheel.api.visual.BlockEntityVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(
        targets = "dev.engine_room.flywheel.impl.visualization.storage.BlockEntityStorage",
        remap = false)
public abstract class FlywheelVisualOwnershipMixins {
    @WrapMethod(
            method =
                    "createRaw(Ldev/engine_room/flywheel/api/visualization/VisualizationContext;Lnet/minecraft/world/level/block/entity/BlockEntity;F)Ldev/engine_room/flywheel/api/visual/BlockEntityVisual;")
    private BlockEntityVisual<?> radiance$visualOwner(
            VisualizationContext context,
            BlockEntity entity,
            float partialTick,
            Operation<BlockEntityVisual<?>> original) {
        try (var scope = FlywheelHandoffs.constructing(entity)) {
            var visual = original.call(context, entity, partialTick);
            scope.completed();
            return visual;
        }
    }
}
