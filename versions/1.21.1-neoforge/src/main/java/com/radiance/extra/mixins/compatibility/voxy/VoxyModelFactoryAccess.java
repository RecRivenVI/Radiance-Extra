package com.radiance.extra.mixins.compatibility.voxy;

import java.util.concurrent.ConcurrentLinkedDeque;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = ModelFactory.class, remap = false)
public interface VoxyModelFactoryAccess {
    @Invoker("getBlockLightEmission")
    static int radiance$sourceEmission(BlockState state) {
        throw new AssertionError();
    }

    @Accessor("uploadResults")
    ConcurrentLinkedDeque<?> radiance$uploads();
}
