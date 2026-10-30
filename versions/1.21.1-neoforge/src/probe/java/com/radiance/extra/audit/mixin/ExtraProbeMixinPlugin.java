package com.radiance.extra.audit.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ExtraProbeMixinPlugin implements IMixinConfigPlugin {
    private final com.radiance.audit.mixin.AuditMixinPlugin policy =
            new com.radiance.audit.mixin.AuditMixinPlugin();

    public void onLoad(String p) {
        policy.onLoad(p);
    }

    public boolean shouldApplyMixin(String t, String m) {
        // Do not link optional fixture classes from an unrelated source's runtime.
        String mode = System.getProperty("radiance.audit.worldMaterials134", "");
        if (m.endsWith(".LevititePonderDrawMixin") && !mode.equals("levitite-ponder")) return false;
        if (m.endsWith(".LevititeStressMixin") && !mode.equals("levitite-stress")) return false;
        return policy.shouldApplyMixin(t, m);
    }

    public void acceptTargets(Set<String> m, Set<String> o) {}

    public List<String> getMixins() {
        return null;
    }

    public void preApply(String t, ClassNode n, String m, IMixinInfo i) {}

    public void postApply(String t, ClassNode n, String m, IMixinInfo i) {}

    public String getRefMapperConfig() {
        return null;
    }
}
