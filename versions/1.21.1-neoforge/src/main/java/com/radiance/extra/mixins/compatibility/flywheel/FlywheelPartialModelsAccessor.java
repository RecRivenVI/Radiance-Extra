package com.radiance.extra.mixins.compatibility.flywheel;

import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = PartialModel.class, remap = false)
public interface FlywheelPartialModelsAccessor {
    @Accessor("ALL")
    static ConcurrentMap<ResourceLocation, PartialModel> radiance$all() {
        throw new AssertionError("Mixin accessor");
    }
}
