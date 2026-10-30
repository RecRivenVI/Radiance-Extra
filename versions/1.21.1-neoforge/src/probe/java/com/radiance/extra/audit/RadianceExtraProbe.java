package com.radiance.extra.audit;

import com.radiance.audit.AuditConfiguration;
import com.radiance.audit.ExperimentAccess;
import com.radiance.audit.ProbeSources;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.common.Mod;

@Mod(value = "radiance_extra_probe", dist = Dist.CLIENT)
public final class RadianceExtraProbe {
    public RadianceExtraProbe() {
        if (!AuditConfiguration.enabled() || !ExperimentAccess.permitted()) return;
        LineWorldProbe.register();
        ProbeSources.register(
                mc -> {
                    if (net.neoforged.fml.ModList.get().isLoaded("sable"))
                        LargeSublevelProbe.poll(mc);
                    SpringMenuProbe.poll(mc);
                    DiagramParityProbe.poll(mc);
                },
                mc -> {
                    if (Boolean.getBoolean("radiance.audit.veilResources")
                            && net.neoforged.fml.ModList.get().isLoaded("veil"))
                        VeilResourceProbe.poll(mc);
                    CatnipRasterProbe.poll(mc);
                    RasterSimulatedProbe.poll(mc);
                    SpringWorldProbe.poll(mc);
                    if (net.neoforged.fml.ModList.get().isLoaded("aeronautics")) {
                        LevititeWorldProbe.poll(mc);
                        LevititeStressProbe.poll(mc);
                        LevititePonderProbe.poll(mc);
                    }
                    LineWorldProbe.poll(mc);
                    VoxyWorldProbe.poll(mc);
                    if (net.neoforged.fml.ModList.get().isLoaded("sable")) {
                        SableHandoffProbe.poll(mc);
                        PlatformSourceProbe.poll(mc);
                    }
                });
    }
}
