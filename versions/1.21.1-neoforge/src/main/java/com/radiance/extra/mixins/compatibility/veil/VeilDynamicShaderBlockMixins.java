package com.radiance.extra.mixins.compatibility.veil;

import com.radiance.extra.compatibility.veil.VeilBlockSnapshot;
import com.radiance.extra.compatibility.veil.VeilBlockState;
import com.radiance.extra.compatibility.veil.VeilShaderBlockData;
import foundry.veil.impl.client.render.shader.block.DynamicShaderBlockImpl;
import foundry.veil.impl.client.render.shader.block.ShaderBlockImpl;
import java.nio.ByteBuffer;
import java.util.function.BiConsumer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = DynamicShaderBlockImpl.class, remap = false)
public abstract class VeilDynamicShaderBlockMixins implements VeilShaderBlockData {

    @Shadow @Final private BiConsumer<Object, ByteBuffer> serializer;

    @Shadow
    public abstract int getSize();

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$skipOpenGlBlockBinding(int index, CallbackInfo ci) {
        ci.cancel();
    }

    @Unique private final VeilBlockSnapshot radiance$snapshot = new VeilBlockSnapshot();

    @Inject(method = "unbind", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$unbindCpuBlock(int index, CallbackInfo ci) {
        com.radiance.extra.compatibility.veil.VeilShaderAdapter.unbindBlock(
                (foundry.veil.api.client.render.shader.block.ShaderBlock<?>) (Object) this);
        ci.cancel();
    }

    @Override
    public int radiance$blockSize() {
        return this.getSize();
    }

    @Override
    public void radiance$writeBlock(ByteBuffer destination) {
        VeilBlockState state = (VeilBlockState) this;
        Object value = ((ShaderBlockImpl<?>) (Object) this).getValue();
        this.radiance$snapshot.write(
                value, this.getSize(), state.radiance$isDirty(), this.serializer, destination);
        state.radiance$setDirty(false);
    }

    @Override
    public void radiance$releaseBlock() {
        this.radiance$snapshot.close();
    }
}
