package com.radiance.extra.mixins.compatibility.voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.radiance.extra.compatibility.voxy.VoxySourceMaterials;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import java.util.List;
import me.cortex.voxy.client.core.model.ColourDepthTextureData;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.model.ModelLayer;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ModelFactory.class, remap = false)
public abstract class VoxySourceMaterialMixins {
    @Unique private VoxySourceMaterials.Bake radiance$bake;

    @Inject(method = "processTextureBakeResult", at = @At("HEAD"))
    private void radiance$sourceMaterial(
            int id,
            BlockState state,
            ColourDepthTextureData[] faces,
            boolean shaded,
            boolean darkened,
            ModelLayer layer,
            CallbackInfoReturnable<?> callback) {
        radiance$bake = new VoxySourceMaterials.Bake(state, layer);
    }

    @Inject(method = "processTextureBakeResult", at = @At("RETURN"))
    private void radiance$publishMaterial(
            int id,
            BlockState state,
            ColourDepthTextureData[] faces,
            boolean shaded,
            boolean darkened,
            ModelLayer layer,
            CallbackInfoReturnable<?> callback) {
        ModelFactory factory = (ModelFactory) (Object) this;
        radiance$bake.publish(factory, factory.getModelId(id));
        radiance$bake = null;
    }

    @WrapOperation(
            method = "processTextureBakeResult",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lme/cortex/voxy/client/core/model/ModelFactory;isBiomeDependentColour(Ljava/util/List;Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean radiance$tintIdentity(
            List<?> sources, BlockState state, Operation<Boolean> original) {
        boolean result = original.call(sources, state);
        radiance$bake.biomeDependent(result);
        return result;
    }

    @WrapOperation(
            method = "processTextureBakeResult",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;getInt(Ljava/lang/Object;)I"))
    private int radiance$lookup(
            Object2IntOpenHashMap<Object> map, Object key, Operation<Integer> original) {
        return original.call(map, radiance$bake.key(key));
    }

    @WrapOperation(
            method = "processTextureBakeResult",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lit/unimi/dsi/fastutil/objects/Object2IntOpenHashMap;put(Ljava/lang/Object;I)I"))
    private int radiance$intern(
            Object2IntOpenHashMap<Object> map, Object key, int value, Operation<Integer> original) {
        return original.call(map, radiance$bake.key(key), value);
    }
}
