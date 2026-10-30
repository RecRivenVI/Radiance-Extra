package com.radiance.extra.mixins.compatibility.veil;

import com.mojang.blaze3d.vertex.VertexFormat;
import com.radiance.extra.compatibility.veil.VeilShaderAdapter;
import foundry.veil.api.client.render.shader.ShaderSourceSet;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.shader.compiler.ShaderCompiler;
import foundry.veil.api.client.render.shader.compiler.ShaderException;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.io.IOException;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(value = ShaderProgramImpl.class, remap = false)
public abstract class VeilShaderProgramImplMixins {

    @Redirect(
            method = "restoreBlendState",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Lorg/lwjgl/opengl/GL20C;glBlendEquationSeparate(II)V"),
            remap = false)
    private static void radiance$restoreVulkanBlendEquation(int color, int alpha) {
        VeilShaderAdapter.restoreBlendEquation(color, alpha);
    }

    @Shadow @Final private ResourceLocation name;
    @Shadow @Final private Int2ObjectMap<ShaderProgramImpl.CompiledProgram> programs;
    @Shadow @Final private Object2ObjectMap<CharSequence, ShaderBlock<?>> shaderBlocks;
    @Shadow private ProgramDefinition definition;
    @Shadow private ShaderProgramImpl.CompiledProgram compiledProgram;

    @Shadow
    protected abstract void applyProgram(ShaderProgramImpl.CompiledProgram program);

    @Inject(method = "recompile", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$compileVulkanProgram(
            int activeBuffers, ShaderSourceSet sourceSet, ShaderCompiler compiler, CallbackInfo ci)
            throws ShaderException, IOException {
        ShaderProgramImpl.CompiledProgram replacement =
                VeilShaderAdapter.compileProgram(
                        this.name, activeBuffers, this.definition, sourceSet, compiler);
        VeilShaderAdapter.replaceProgram(
                (ShaderProgram) (Object) this,
                this.programs,
                activeBuffers,
                this.compiledProgram,
                replacement,
                this::applyProgram);
        ci.cancel();
    }

    @Inject(method = "applyProgram", at = @At("RETURN"), remap = false)
    private void radiance$activateVariant(
            ShaderProgramImpl.CompiledProgram program, CallbackInfo ci) {
        VeilShaderAdapter.attachProgram((ShaderProgram) (Object) this, program);
    }

    @Inject(method = "bind", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$bindVulkanProgram(CallbackInfo ci) {
        ShaderProgram program = (ShaderProgram) (Object) this;
        VeilShaderAdapter.bindProgramAndBlocks(program, this.shaderBlocks, this.definition);
        ci.cancel();
    }

    @Inject(method = "setTexture", at = @At("HEAD"), remap = false)
    private void radiance$captureTexture(
            CharSequence name, int target, int textureId, int samplerId, CallbackInfo ci) {
        VeilShaderAdapter.setTexture((ShaderProgram) (Object) this, name, textureId);
    }

    @Inject(method = "setDefaultUniforms", at = @At("HEAD"), cancellable = true, remap = false)
    private void radiance$setVulkanDefaultUniforms(
            VertexFormat.Mode mode, Matrix4fc modelView, Matrix4fc projection, CallbackInfo ci) {
        VeilShaderAdapter.setDefaultUniforms(
                (ShaderProgram) (Object) this, mode, modelView, projection);
        ci.cancel();
    }

    @Inject(method = "freeInternal", at = @At("HEAD"), remap = false)
    private void radiance$releaseVulkanProgram(CallbackInfo ci) {
        VeilShaderAdapter.releaseProgram((ShaderProgram) (Object) this);
    }
}
