package com.radiance.extra.integration;

import com.radiance.api.render.SourceIntegration;
import com.radiance.extra.compatibility.flywheel.FlywheelRenderBridge;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class InstancingIntegration implements SourceIntegration.World {
    public void worldClosed() {
        com.radiance.extra.compatibility.flywheel.FlywheelHandoffs.close();
    }

    public String id() {
        return "radiance_extra:flywheel";
    }

    public Frame begin(FrameInput i) {
        return FlywheelRenderBridge.begin(
                i.renderer(),
                i.level(),
                i.buffers(),
                i.modelView(),
                i.projection(),
                i.camera(),
                i.delta());
    }

    public boolean ownsEntity(ClientLevel l, Entity e) {
        return FlywheelRenderBridge.shouldSkipVanillaEntity(l, e);
    }

    public boolean ownsBlockEntity(ClientLevel l, BlockEntity e) {
        return FlywheelRenderBridge.shouldSkipVanillaBlockEntity(l, e);
    }
}
