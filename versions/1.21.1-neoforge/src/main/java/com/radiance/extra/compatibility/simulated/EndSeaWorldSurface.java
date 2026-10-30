package com.radiance.extra.compatibility.simulated;

import com.radiance.api.render.*;
import com.radiance.client.proxy.vulkan.TextureProxy;
import com.radiance.client.texture.TextureTasks;
import com.radiance.render.contract.RenderAbi;
import dev.simulated_team.simulated.content.end_sea.EndSeaPhysicsData;
import dev.simulated_team.simulated.content.end_sea.EndSeaShadowRenderer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.lwjgl.system.MemoryUtil;

/** Simulated interpretation; the shared renderer receives planes and a projected material mask. */
final class EndSeaWorldSurface implements AutoCloseable {
    private static final int LAYERS = 48;
    // The source adds 48 real surfaces at unit full-bright strength. This fixed
    // budget preserves that reference without tying energy to a sample count.
    private static final float SOURCE_EMISSION_BUDGET = 48;
    private static final double[][] COLOURS = {
        {.022087, .098399, .110818},
        {.011892, .095924, .089485},
        {.027636, .101689, .100326},
        {.046564, .109883, .114838},
        {.064901, .117696, .097189},
        {.063761, .086895, .123646},
        {.084817, .111994, .166380},
        {.097489, .154120, .091064},
        {.106152, .131144, .195191},
        {.097721, .110188, .187229},
        {.133516, .138278, .148582},
        {.070006, .243332, .235792},
        {.196766, .142899, .214696},
        {.047281, .315338, .321970},
        {.204675, .390010, .302066},
        {.080955, .314821, .661491}
    };
    private final LayeredWorldMaterial material = new LayeredWorldMaterial();
    private final RenderResources.Texture palette =
            RenderResources.rgba8(
                    16, 1, 1, RenderResources.Sampling.NEAREST, RenderResources.Sampling.NEAREST);
    private final ByteBuffer vertices =
            MemoryUtil.memCalloc(LAYERS * 4 * 128).order(ByteOrder.nativeOrder());
    private final LocalModelBatch batch = new LocalModelBatch(1);
    private final long identity = RenderResources.geometryIdentity();
    private final long model = identity << 32 | RenderAbi.GEOMETRY_WORLD_TRANSPARENT;
    private final long history = Long.MIN_VALUE | (1L << 60) | identity;
    private final Matrix4f pose = new Matrix4f();

    EndSeaWorldSurface() {
        ByteBuffer colours = MemoryUtil.memAlloc(64);
        try {
            for (int i = 0; i < 16; i++) {
                double[] colour = COLOURS[i];
                double norm =
                        Math.sqrt(
                                colour[0] * colour[0]
                                        + colour[1] * colour[1]
                                        + colour[2] * colour[2]);
                for (int c = 0; c < 3; c++) colours.put((byte) (int) (colour[c] / norm * 255));
                colours.put((byte) 255);
            }
            colours.flip();
            palette.upload(0, 0, 0, 16, 1, colours);
            float radius = EndSeaShadowRenderer.SHADOW_VOLUME_RADIUS;
            int[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
            for (int plane = 0; plane < LAYERS; plane++)
                for (int corner = 0; corner < 4; corner++) {
                    int at = (plane * 4 + corner) * 128, colour = (plane % 16) * 4;
                    vertices.putFloat(at, corners[corner][0] * radius)
                            .putFloat(at + 4, -plane * .5f)
                            .putFloat(at + 8, corners[corner][1] * radius);
                    vertices.putInt(at + 12, 1).putFloat(at + 20, 1).putInt(at + 28, 1);
                    for (int c = 0; c < 3; c++)
                        vertices.putFloat(at + 32 + c * 4, (colours.get(colour + c) & 255) / 255f);
                    vertices.putFloat(at + 44, 1).putInt(at + 48, 1);
                    vertices.putFloat(at + 56, (corners[corner][0] + 1) * .5f)
                            .putFloat(at + 60, (corners[corner][1] + 1) * .5f);
                    vertices.putInt(at + 76, material.owner().id())
                            .putInt(at + 124, RenderAbi.ALPHA_ADDITIVE);
                }
        } finally {
            MemoryUtil.memFree(colours);
        }
    }

    void collect() {
        var minecraft = Minecraft.getInstance();
        var physics = EndSeaPhysicsData.of(minecraft.level);
        var framebuffer = EndSeaShadowRenderer.getShadowsFramebuffer();
        if (physics == null || framebuffer == null || minecraft.player == null) return;
        var sky =
                TextureProxy.TASKS.owner(
                        minecraft
                                .getTextureManager()
                                .getTexture(
                                        ResourceLocation.withDefaultNamespace(
                                                "textures/entity/end_portal.png"))
                                .getId());
        TextureTasks.Owner depth =
                TextureProxy.TASKS.owner(framebuffer.getDepthTextureAttachment().getId());
        TextureTasks.Owner strength =
                TextureProxy.TASKS.owner(framebuffer.getColorTextureAttachment(0).getId());
        if (sky == null || depth == null || strength == null) return;
        var origin = EndSeaShadowRenderer.getLastRenderOrigin();
        float radius = EndSeaShadowRenderer.SHADOW_VOLUME_RADIUS;
        float phase =
                (float)
                        (((minecraft.player.tickCount - (minecraft.level.getGameTime() % 24000L))
                                        / 2000.0)
                                % 1);
        material.images(sky, null, depth, strength, palette.owner())
                .program(2, LAYERS, true, true)
                .uv(1 / 25f, 1 / 25f, radius / 25f, radius / 25f + phase, 0, 12)
                .depthMask((float) physics.startY() - radius + .5f, radius - .5f, 10, 3)
                .planes(
                        origin.x(),
                        (float) physics.startY(),
                        origin.z(),
                        radius,
                        -.5f,
                        SOURCE_EMISSION_BUDGET,
                        1,
                        .025f);
        if (!material.publish()) return;
        batch.reset();
        batch.source(this);
        if (!batch.selectUnchanged(
                history,
                this,
                model,
                RenderAbi.GEOMETRY_WORLD_TRANSPARENT,
                material.owner(),
                LAYERS * 4,
                origin.x(),
                physics.startY(),
                origin.z(),
                RenderAbi.QUERY_WORLD,
                pose,
                "world_layers",
                com.radiance.render.contract.DrawStateSnapshot.IDENTITY,
                null))
            batch.add(
                    model,
                    history,
                    RenderAbi.GEOMETRY_WORLD_TRANSPARENT,
                    material.owner(),
                    vertices,
                    LAYERS * 4,
                    origin.x(),
                    physics.startY(),
                    origin.z(),
                    RenderAbi.QUERY_WORLD,
                    pose,
                    "world_layers");
        batch.submit();
        com.radiance.api.audit.DrawingAudit.consumed("simulated.end_sea.world_planes");
    }

    public void close() {
        PersistentInstances.removeOwner(this);
        batch.close();
        material.close();
        palette.close();
        MemoryUtil.memFree(vertices);
    }
}
