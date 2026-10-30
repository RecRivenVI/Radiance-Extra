package com.radiance.extra.integration;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.api.render.SourceIntegration;
import com.radiance.extra.compatibility.sable.SableRenderSectionCompatibility;
import com.radiance.extra.compatibility.sable.SableSubLevelBridge;
import com.radiance.extra.compatibility.sable.SableVertexConsumerCompat;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;

public final class SubsceneIntegration
        implements SourceIntegration.World, SourceIntegration.Spatial, SourceIntegration.Geometry {
    public void resourcesInvalidated() {
        com.radiance.extra.compatibility.sable.SableSceneRegistry.resourcesInvalidated();
    }

    public void blockEntityChanged(net.minecraft.world.level.block.entity.BlockEntity entity) {
        com.radiance.extra.compatibility.sable.SableSceneRegistry.blockEntityChanged(entity);
    }

    public void worldClosed() {
        com.radiance.extra.compatibility.sable.SableSceneRegistry.close();
    }

    public String id() {
        return "radiance_extra:sable";
    }

    public Object blockSource(net.minecraft.world.level.block.entity.BlockEntity entity) {
        return SableSubLevelBridge.blockSource(entity);
    }

    public void sleepingPose(
            net.minecraft.world.entity.LivingEntity entity,
            com.mojang.blaze3d.vertex.PoseStack pose) {
        if (entity.getBedOrientation() == null) return;
        var bed = entity.getSleepingPos();
        if (bed.isPresent()
                && dev.ryanhcode.sable.Sable.HELPER.getContaining(entity.level(), bed.get())
                        instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel sublevel)
            pose.mulPose(new org.joml.Quaternionf(sublevel.renderPose().orientation()));
    }

    public org.joml.Quaternionf nameOrientation(
            Entity entity, float partial, org.joml.Quaternionf camera) {
        if (!dev.ryanhcode.sable.api.entity.EntitySubLevelUtil.shouldKick(entity)) return camera;
        float sourcePartial =
                net.minecraft.client.Minecraft.getInstance()
                        .getTimer()
                        .getGameTimeDeltaPartialTick(true);
        var orientation =
                dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation.EntitySubLevelRotationHelper
                        .getEntityOrientation(
                                entity,
                                s -> ((dev.ryanhcode.sable.sublevel.ClientSubLevel) s).renderPose(),
                                sourcePartial,
                                dev.ryanhcode.sable.mixinhelpers.camera.camera_rotation
                                        .EntitySubLevelRotationHelper.Type.ENTITY);
        return orientation == null
                ? camera
                : new org.joml.Quaternionf(orientation).conjugate().mul(camera);
    }

    public void update(ClientLevel l, Camera c, float t) {
        SableSubLevelBridge.update(l, c, t);
    }

    public void blocks(ClientLevel l, float t) {
        SableSubLevelBridge.queueSingleBlocks(l, t);
    }

    public void blockEntities(ClientLevel l, Camera c, BlockEntityRenderDispatcher d, float t) {
        SableSubLevelBridge.queueBlockEntities(l, c, d, t);
    }

    public void debug(ClientLevel l, Camera c, float t) {
        SableSubLevelBridge.queueDebugBoxes(l, c, t);
    }

    public void prepare(ClientLevel l, Camera c, Frustum f, boolean spectator) {
        SableSubLevelBridge.prepareRenderer(l, c, f, spectator);
    }

    public EntityTransform entityTransform(Entity e, float t, double x, double y, double z) {
        var p = SableSubLevelBridge.entityRenderTransform(e, t, x, y, z);
        return p == null
                ? null
                : new EntityTransform(
                        p.position(),
                        p.orientation(),
                        p.rotatesModel(),
                        com.radiance.extra.integration.ExtraLineSources.SABLE,
                        p.placement());
    }

    public Outline outline(ClientLevel l, Camera c, BlockPos p, float t) {
        return SableSubLevelBridge.blockOutlineContext(l, c, p, t);
    }

    public boolean sectionChanged(SectionRenderDispatcher.RenderSection s) {
        return SableRenderSectionCompatibility.handleDirty(s);
    }

    public boolean isExternal(SectionRenderDispatcher.RenderSection s) {
        return SableRenderSectionCompatibility.isExternal(s);
    }

    public VertexConsumer bulkConsumer(VertexConsumer c, boolean pathTraced) {
        return SableVertexConsumerCompat.bulkDataConsumer(c, pathTraced);
    }
}
