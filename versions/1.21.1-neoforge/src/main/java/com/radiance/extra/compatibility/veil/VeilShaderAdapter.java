package com.radiance.extra.compatibility.veil;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.radiance.client.proxy.vulkan.PipelineStateProxy;
import com.radiance.client.render.AppliedShaderState;
import com.radiance.client.shader.ExternalShaderMetadata;
import com.radiance.client.shader.ShaderDefinition;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.ShaderSourceSet;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import foundry.veil.api.client.render.shader.compiler.CompiledShader;
import foundry.veil.api.client.render.shader.compiler.ShaderCompiler;
import foundry.veil.api.client.render.shader.compiler.ShaderException;
import foundry.veil.api.client.render.shader.compiler.VeilShaderSource;
import foundry.veil.api.client.render.shader.program.ProgramDefinition;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.api.client.render.shader.program.ShaderUniformCache;
import foundry.veil.impl.client.render.shader.program.ShaderProgramImpl;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4fc;

/**
 * Draw-facing facade for Veil programs; the parser/compiler remains in {@link VeilShaderBridge}.
 */
public final class VeilShaderAdapter {
    private VeilShaderAdapter() {}

    public static void applyWrapper(ShaderProgramImpl.Wrapper wrapper) {
        RenderSystem.assertOnRenderThread();
        AppliedShaderState.apply(wrapper);
    }

    public static void clearWrapper() {
        AppliedShaderState.clear();
    }

    public static void restoreBlendEquation(int color, int alpha) {
        PipelineStateProxy.ColorBlendState.glSetBlendOpSeparate(color, alpha);
    }

    public static void setDefaultUniforms(
            ShaderProgram program,
            VertexFormat.Mode mode,
            Matrix4fc modelView,
            Matrix4fc projection) {
        if (!VeilShaderBridge.hasProgram(program)) return;
        VeilShaderBridge.setDefaultUniforms(program, mode, modelView, projection);
    }

    public static CompiledShader compileStage(int type, VeilShaderSource source)
            throws ShaderException {
        return VeilShaderBridge.compileStage(type, source);
    }

    public static ShaderProgramImpl.CompiledProgram compileProgram(
            ResourceLocation name,
            int activeBuffers,
            ProgramDefinition definition,
            ShaderSourceSet sourceSet,
            ShaderCompiler compiler)
            throws ShaderException, IOException {
        return VeilShaderBridge.compileProgram(
                name, activeBuffers, definition, sourceSet, compiler);
    }

    public static void replaceProgram(
            ShaderProgram owner,
            Int2ObjectMap<ShaderProgramImpl.CompiledProgram> programs,
            int activeBuffers,
            ShaderProgramImpl.CompiledProgram previousActive,
            ShaderProgramImpl.CompiledProgram replacement,
            java.util.function.Consumer<ShaderProgramImpl.CompiledProgram> apply) {
        replaceProgram(
                programs,
                activeBuffers,
                previousActive,
                replacement,
                apply,
                () -> VeilShaderBridge.releaseProgram(owner),
                compiled -> VeilShaderBridge.attachProgram(owner, compiled),
                ShaderProgramImpl.CompiledProgram::free);
    }

    /** Commit only after apply succeeds; a failed reload keeps the previous live variant. */
    static void replaceProgram(
            Int2ObjectMap<ShaderProgramImpl.CompiledProgram> programs,
            int activeBuffers,
            ShaderProgramImpl.CompiledProgram previousActive,
            ShaderProgramImpl.CompiledProgram replacement,
            java.util.function.Consumer<ShaderProgramImpl.CompiledProgram> apply,
            Runnable detach,
            java.util.function.Consumer<ShaderProgramImpl.CompiledProgram> attach,
            java.util.function.Consumer<ShaderProgramImpl.CompiledProgram> free) {
        try {
            apply.accept(replacement);
            attach.accept(replacement);
        } catch (RuntimeException | Error failure) {
            try {
                if (previousActive != null) {
                    apply.accept(previousActive);
                    attach.accept(previousActive);
                } else detach.run();
            } catch (RuntimeException | Error rollback) {
                failure.addSuppressed(rollback);
            }
            try {
                free.accept(replacement);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
        ShaderProgramImpl.CompiledProgram previous = programs.put(activeBuffers, replacement);
        if (previous != null && previous != replacement) free.accept(previous);
    }

    public static void attachProgram(
            ShaderProgram owner, ShaderProgramImpl.CompiledProgram compiled) {
        VeilShaderBridge.attachProgram(owner, compiled);
    }

    public static void bindProgramAndBlocks(
            ShaderProgram program,
            Object2ObjectMap<CharSequence, ShaderBlock<?>> shaderBlocks,
            ProgramDefinition definition) {
        VeilShaderBridge.bindProgram(program);
        if (!VeilShaderBridge.hasProgram(program)) return;
        try {
            VeilRenderSystem.clearShaderBlocks();
            // Keep Veil's binding ownership: packed blocks are transient, global blocks are not.
            // Its binding callbacks also update the CPU block map used by the Vulkan wrapper.
            for (Object2ObjectMap.Entry<CharSequence, ShaderBlock<?>> entry :
                    shaderBlocks.object2ObjectEntrySet()) {
                VeilRenderSystem.bind(entry.getKey(), entry.getValue());
            }
            var blend = definition == null ? null : definition.blendMode();
            if (blend != null) {
                ShaderProgramImpl.saveBlendState(blend.hasEquation());
                if (blend.hasEquation())
                    PipelineStateProxy.ColorBlendState.glSetBlendOpSeparate(
                            blend.colorEquation().getGlType(), blend.alphaEquation().getGlType());
                RenderSystem.blendFuncSeparate(
                        blend.srcColorFactor(),
                        blend.dstColorFactor(),
                        blend.srcAlphaFactor(),
                        blend.dstAlphaFactor());
            }
        } catch (RuntimeException | Error failure) {
            try {
                unbindProgram();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public static void setTexture(ShaderProgram program, CharSequence name, int textureId) {
        VeilShaderBridge.setTexture(program, name, textureId);
    }

    public static void releaseProgram(ShaderProgram program) {
        VeilShaderBridge.releaseProgram(program);
    }

    public static void releaseStage(int nativeId) {
        VeilShaderBridge.releaseStage(nativeId);
    }

    public static void bindBlock(CharSequence name, ShaderBlock<?> block) {
        VeilShaderBridge.bindBlock(name, block);
    }

    public static void unbindBlock(ShaderBlock<?> block) {
        VeilShaderBridge.unbindBlock(block);
    }

    public static ExternalShaderMetadata metadata(
            ShaderProgram program, VertexFormat.Mode drawMode) {
        return VeilShaderBridge.metadata(program, drawMode);
    }

    public static void writeUniforms(
            ShaderProgram program, ShaderDefinition definition, ByteBuffer destination) {
        VeilShaderBridge.writeUniforms(program, definition, destination);
    }

    public static VertexFormat vertexFormat(ShaderProgramImpl.CompiledProgram compiled) {
        return VeilShaderBridge.format(compiled);
    }

    public static void freeCompiledProgram(
            ShaderProgramImpl.CompiledProgram compiled,
            Int2ObjectMap<CompiledShader> shaders,
            ShaderUniformCache cache,
            Set<String> dependencies) {
        VeilShaderBridge.releaseCompiledProgram(compiled);
        shaders.values().forEach(CompiledShader::free);
        shaders.clear();
        cache.clear();
        dependencies.clear();
    }

    public static void populateUniformCache(
            ShaderUniformCache cache,
            Object2ObjectMap<String, ShaderUniformCache.Uniform> samplers,
            Object2ObjectMap<String, ShaderUniformCache.Uniform> uniforms,
            Object2ObjectMap<String, ShaderUniformCache.UniformBlock> uniformBlocks,
            Object2ObjectMap<String, ShaderUniformCache.StorageBlock> storageBlocks) {
        samplers.clear();
        uniforms.clear();
        uniformBlocks.clear();
        storageBlocks.clear();
        uniforms.putAll(VeilShaderBridge.uniforms(cache));
        samplers.putAll(VeilShaderBridge.samplers(cache));
        uniformBlocks.putAll(VeilShaderBridge.uniformBlocks(cache));
    }

    public static void bindProgram(ShaderProgram program) {
        VeilShaderBridge.bindProgram(program);
    }

    public static void unbindProgram() {
        try {
            VeilRenderSystem.clearShaderBlocks();
        } finally {
            VeilShaderBridge.unbindProgram();
            ShaderProgramImpl.restoreBlendState();
        }
    }
}
