package com.radiance.extra;

import com.radiance.api.render.RenderPlatform;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;

@Mod(value = "radiance_extra", dist = Dist.CLIENT)
public final class RadianceExtra {
    public RadianceExtra() {
        var mods = ModList.get();
        for (String id :
                java.util.List.of(
                        "sable",
                        "flywheel",
                        "simulated",
                        "aeronautics",
                        "veil",
                        "voxy",
                        "create",
                        "ponder"))
            RenderPlatform.sourceAvailability(
                    "radiance_extra:" + id,
                    mods.isLoaded(id) ? "来源已安装；专项入口已启用，是否使用以运行记录为准" : "未安装对应模组；适配器未加载");
        if (mods.isLoaded("sable"))
            RenderPlatform.register(
                    RenderPlatform.API_VERSION,
                    new com.radiance.extra.integration.SubsceneIntegration());
        if (mods.isLoaded("flywheel"))
            RenderPlatform.register(
                    RenderPlatform.API_VERSION,
                    new com.radiance.extra.integration.InstancingIntegration());
        if (mods.isLoaded("simulated"))
            RenderPlatform.register(
                    RenderPlatform.API_VERSION,
                    new com.radiance.extra.integration.SimulatedIntegration());
        if (mods.isLoaded("aeronautics"))
            RenderPlatform.register(
                    RenderPlatform.API_VERSION,
                    new com.radiance.extra.integration.AeronauticsIntegration());
        if (mods.isLoaded("veil"))
            RenderPlatform.register(
                    RenderPlatform.API_VERSION,
                    new com.radiance.extra.integration.VeilIntegration());
        if (mods.isLoaded("voxy"))
            RenderPlatform.register(
                    RenderPlatform.API_VERSION,
                    new com.radiance.extra.integration.DistantSceneIntegration());
        if (mods.isLoaded("create") || mods.isLoaded("ponder"))
            RenderPlatform.register(
                    RenderPlatform.API_VERSION,
                    new com.radiance.extra.integration.OutlineIntegration());
    }
}
