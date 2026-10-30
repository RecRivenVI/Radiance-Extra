package com.radiance.extra.audit;

import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.radiance.client.proxy.vulkan.FramebufferProxy;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.extra.compatibility.veil.VeilShaderBlockData;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.block.ShaderBlock;
import java.nio.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.GlStateBackup;
import org.lwjgl.system.MemoryUtil;

/** Real mixed-in source objects and Vulkan readback, before and after the fixture's reload. */
final class VeilResourceProbe {
    private static Object previousProgram;
    private static int checkpoints;

    static void poll(Minecraft mc) {
        if (!Boolean.getBoolean("radiance.audit.veilResources")
                || mc.level == null
                || mc.getOverlay() != null
                || checkpoints == 2) return;
        var program =
                VeilRenderSystem.renderer()
                        .getShaderManager()
                        .getShader(ResourceLocation.fromNamespaceAndPath("veil", "blit_screen"));
        if (program == null || program == previousProgram) return;
        try {
            checkBlocks(false);
            checkBlocks(true);
            checkBlit(mc, program);
            previousProgram = program;
            checkpoints++;
            Files.writeString(
                    Path.of(System.getProperty("validation.result"), "veil-resources.json"),
                    "{\"checkpoints\":"
                            + checkpoints
                            + ",\"blockCaching\":true,\"screenTriangleReadback\":true,\"performanceMeasured\":false}\n");
            com.mojang.logging.LogUtils.getLogger()
                    .info(
                            "VEIL_RESOURCES checkpoint={} block cache and Vulkan blit passed",
                            checkpoints);
        } catch (Exception | LinkageError | AssertionError failure) {
            throw new IllegalStateException("Veil resource regression", failure);
        }
    }

    private static void checkBlocks(boolean dynamic) {
        var calls = new AtomicInteger();
        java.util.function.BiConsumer<int[], ByteBuffer> serializer =
                (value, bytes) -> {
                    if (!bytes.isDirect())
                        throw new AssertionError("Serializer needs native memory");
                    calls.incrementAndGet();
                    bytes.putInt(value[0]);
                };
        ShaderBlock<int[]> block =
                dynamic
                        ? ShaderBlock.dynamic(ShaderBlock.BufferBinding.UNIFORM, 16, serializer)
                        : ShaderBlock.withSize(ShaderBlock.BufferBinding.UNIFORM, 16, serializer);
        try {
            var data = (VeilShaderBlockData) block;
            int[] value = {17};
            block.set(value);
            VeilRenderSystem.bind("RadianceVeilProbe", block);
            ByteBuffer first = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
            data.radiance$writeBlock(first);
            for (int i = 0; i < 32; i++) data.radiance$writeBlock(ByteBuffer.allocate(16));
            if (calls.get() != 1 || first.getInt(0) != 17)
                throw new AssertionError("Unchanged block serialized repeatedly");
            value[0] = 23;
            block.set(value);
            ByteBuffer second = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder());
            data.radiance$writeBlock(second);
            if (calls.get() != 2
                    || first.getInt(0) != 17
                    || second.getInt(0) != 23
                    || second.getInt(4) != 0)
                throw new AssertionError("Block copy/dirty/padding ownership");
            if (dynamic) {
                ((foundry.veil.api.client.render.shader.block.DynamicShaderBlock<?>) block)
                        .setSize(32);
                data.radiance$writeBlock(ByteBuffer.allocate(32));
                if (calls.get() != 3) throw new AssertionError("Resize did not serialize");
            }
        } finally {
            block.free();
        }
        var opaque = ShaderBlock.wrapper(ShaderBlock.BufferBinding.UNIFORM, 0);
        try {
            VeilRenderSystem.bind("RadianceVeilOpaqueProbe", opaque);
        } finally {
            opaque.free();
        }
    }

    private static void checkBlit(
            Minecraft mc, foundry.veil.api.client.render.shader.program.ShaderProgram program) {
        var saved = new GlStateBackup();
        RenderSystem.backupGlState(saved);
        int read = FramebufferProxy.boundFramebuffer(FramebufferProxy.READ_FRAMEBUFFER);
        int draw = FramebufferProxy.boundFramebuffer(FramebufferProxy.DRAW_FRAMEBUFFER);
        int[] viewport =
                com.radiance.client.proxy.vulkan.PipelineStateProxy.ViewportState.getViewport();
        TextureTarget target = null;
        DynamicTexture texture = null;
        ByteBuffer pixels = MemoryUtil.memAlloc(16 * 16 * 4);
        try (var scope =
                com.radiance.client.render.RenderCaptureContract.enter(
                        com.radiance.render.contract.DrawTargetContract.ScopeKind.GUI,
                        "audit-veil-resources")) {
            NativeImage image = new NativeImage(1, 1, false);
            image.setPixelRGBA(0, 0, 0xFF4080C0);
            try {
                texture = new DynamicTexture(image);
            } catch (RuntimeException | Error failure) {
                image.close();
                throw failure;
            }
            target = new TextureTarget(16, 16, false, false);
            target.setClearColor(0, 0, 0, 0);
            target.clear(false);
            target.bindWrite(true);
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.disableBlend();
            RenderSystem.disableScissor();
            RenderSystem.colorMask(true, true, true, true);
            program.setTexture("DiffuseSampler", 0x0DE1, texture.getId(), 0);
            program.bind();
            try {
                for (int i = 0; i < 3; i++) {
                    VeilRenderSystem.drawScreenQuad();
                    if (com.radiance.extra.compatibility.veil.VeilShaderBridge.currentProgram()
                            != program)
                        throw new AssertionError("Screen draw lost source program binding");
                }
            } finally {
                foundry.veil.api.client.render.shader.program.ShaderProgram.unbind();
            }
            TextureProxy.downloadTexture(
                    target.getColorTextureId(), 0, 16, 16, 4, MemoryUtil.memAddress(pixels));
            for (int i = 0; i < 16 * 16; i++) {
                int offset = i * 4;
                if (Math.abs(Byte.toUnsignedInt(pixels.get(offset)) - 192) > 1
                        || Math.abs(Byte.toUnsignedInt(pixels.get(offset + 1)) - 128) > 1
                        || Math.abs(Byte.toUnsignedInt(pixels.get(offset + 2)) - 64) > 1
                        || Byte.toUnsignedInt(pixels.get(offset + 3)) != 255)
                    throw new AssertionError(
                            "Blit readback pixel="
                                    + i
                                    + " rgba="
                                    + Integer.toHexString(pixels.getInt(offset)));
            }
        } finally {
            FramebufferProxy.bindFramebufferPair(read, draw);
            if (target != null) target.destroyBuffers();
            if (texture != null) texture.close();
            RenderSystem.restoreGlState(saved);
            RenderSystem.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
            MemoryUtil.memFree(pixels);
        }
    }
}
