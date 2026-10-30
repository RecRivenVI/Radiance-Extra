package com.radiance.extra.mixin;

import java.util.List;
import java.util.Set;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

public final class ExtraMixinPlugin implements IMixinConfigPlugin {
    private static final boolean ENABLED = true;

    public void onLoad(String mixinPackage) {
        // This accessor exposes the existing thread-local cache for diagram cleanup only.
        com.radiance.api.render.ReviewedTransformations.register(
                "radiance_extra.mixins.json:compatibility.simulated.SimulatedDiagramModelCacheAccessor from mod radiance_extra",
                "com/radiance/extra/mixins/compatibility/simulated/SimulatedDiagramModelCacheAccessor.class",
                "78eceb9466b1efcc9f5cb3c65245d657bdf75871c2ebf8705c456d450344b89d");
        // Read-only texture-manager accessor; no geometry, model or render-state mutation.
        com.radiance.api.render.ReviewedTransformations.register(
                "ponder-common.mixins.json:client.accessor.ItemRendererAccessor from mod ponder",
                "net/createmod/ponder/mixin/client/accessor/ItemRendererAccessor.class",
                "26ec1a662aba77eb58eedad1697ad211097bad4bb9f22f75fb3b83f23bfb304b");
        // Only exposes RenderType.create; custom Ponder layers still need their own state contract.
        com.radiance.api.render.ReviewedTransformations.register(
                "ponder-common.mixins.json:client.accessor.RenderTypeAccessor from mod ponder",
                "net/createmod/ponder/mixin/client/accessor/RenderTypeAccessor.class",
                "e8244bd9d3507086401c9c6ea282c7f4f1ab2eead5a390387399ccbf92e4810e");
        // The reviewed hook only prepares Flywheel's own MeshEmitter, never a PT consumer.
        com.radiance.api.render.ReviewedTransformations.register(
                "flywheel.impl.neoforge.mixins.json:ModelBlockRendererMixin from mod flywheel",
                "dev/engine_room/flywheel/impl/mixin/neoforge/ModelBlockRendererMixin.class",
                "a05b6cae35e80683a26be0bdc7e97b5cec5f6dc72f90677d8a1255a21de3629a");
        // Create's accessors are additive. Its custom armor callback remains in actual.render().
        com.radiance.api.render.ReviewedTransformations.register(
                "create.mixins.json:accessor.HumanoidArmorLayerAccessor from mod create",
                "com/simibubi/create/foundation/mixin/accessor/HumanoidArmorLayerAccessor.class",
                "0776ecda141b1be5983544a85e2fbc7db88fe92295e64d2d62927d07f21797b0");
        com.radiance.api.render.ReviewedTransformations.register(
                "create.mixins.json:client.HumanoidArmorLayerMixin from mod create",
                "com/simibubi/create/foundation/mixin/client/HumanoidArmorLayerMixin.class",
                "8a94fdb9fe158ee8a3fd971a9b763ded66916af78a1e916a865a3e7eb0452ed1");
        com.radiance.api.render.ReviewedTransformations.register(
                "create.mixins.json:accessor.FontAccessor from mod create",
                "com/simibubi/create/foundation/mixin/accessor/FontAccessor.class",
                "40459f4ce7322d667c6c9de55ed9d186a619c577618bbe76423a7eea1afbd877");
        // Sable callbacks are retained or supplied at the same source-evaluation boundary.
        com.radiance.api.render.ReviewedTransformations.register(
                "sable.mixins.json:dynamic_directional_shading.ModelBlockRendererMixin from mod sable",
                "dev/ryanhcode/sable/mixin/dynamic_directional_shading/ModelBlockRendererMixin.class",
                "757479f0fb0f38e0bcab7406bf47b54ce8b6461aea976679b47020a2acae7049");
        com.radiance.api.render.ReviewedTransformations.register(
                "sable.mixins.json:respawn_point.sleeping.LivingEntityRendererMixin from mod sable",
                "dev/ryanhcode/sable/mixin/respawn_point/sleeping/LivingEntityRendererMixin.class",
                "10c96cffac92d95e0f240ed5e832b0285b0d43a57e04522c847fada0e4b892fa");
        com.radiance.api.render.ReviewedTransformations.register(
                "sable.mixins.json:entity.entity_rotations_and_riding.EntityRendererMixin from mod sable",
                "dev/ryanhcode/sable/mixin/entity/entity_rotations_and_riding/EntityRendererMixin.class",
                "b83cebd00d8dd6f7667ef2d25eb94942ba7cf8ecc93427e4083876841cbdc2bc");
        com.radiance.api.render.ReviewedTransformations.register(
                "sable.mixins.json:entity.entity_rendering.EntityRendererMixin from mod sable",
                "dev/ryanhcode/sable/mixin/entity/entity_rendering/EntityRendererMixin.class",
                "7fee034a63757ab11f7fbf7bc426423bde171cbe1b870661dda7392dd758b7a1");
        // Factory extensions remain append-only. VeilIntegration checks actual extra shards;
        // unknown additions still require complete state execution, never the declared-state path.
        com.radiance.api.render.ReviewedTransformations.register(
                "veil.neoforge.mixins.json:client.RenderTypeMixin from mod veil",
                "foundry/veil/forge/mixin/client/RenderTypeMixin.class",
                "cade2193ac1def200ee4429ef4e895d72f99abc7a68eaed37e8a9104bc303c0f");
        com.radiance.api.render.ReviewedTransformations.register(
                "veil.rendertype.mixins.json:accessor.RenderStateShardAccessor from mod veil",
                "foundry/veil/mixin/rendertype/accessor/RenderStateShardAccessor.class",
                "9c8a6028c86120c1f8791c5db4663060fcf24597340084bd047e832b5d85b12c");
        com.radiance.api.render.ReviewedTransformations.register(
                "veil.rendertype.mixins.json:accessor.RenderTypeAccessor from mod veil",
                "foundry/veil/mixin/rendertype/accessor/RenderTypeAccessor.class",
                "a5527369f89b5dc523aa090ffc736ea28221a59e5c419bc6743da6ecc610b5ae");
        com.radiance.api.render.ReviewedTransformations.register(
                "veil.rendertype.mixins.json:client.RenderTypeMixin from mod veil",
                "foundry/veil/mixin/rendertype/client/RenderTypeMixin.class",
                "20f31d9cf26ed0729ae437c4d4c38bc70f8829561d031f76429cea2f4c02871a");
        com.radiance.api.render.ReviewedTransformations.register(
                "veil.rendertype.mixins.json:client.CompositeStateMixin from mod veil",
                "foundry/veil/mixin/rendertype/client/CompositeStateMixin.class",
                "94f181785874aae7fe7e5250bd0718fbe7282c3bbf392f0c897ef660fae62309");
        com.radiance.api.render.ReviewedTransformations.register(
                "veil.resource.mixins.json:accessor.ResourceModelManagerAccessor from mod veil",
                "foundry/veil/mixin/resource/accessor/ResourceModelManagerAccessor.class",
                "0dbb155919365e7228542f422d172df42b25060d47490520bf1e8e7c6bd7e7a3");
        com.radiance.api.render.ReviewedTransformations.register(
                "flywheel.impl.mixins.json:ModelPartAccessor from mod flywheel",
                "dev/engine_room/flywheel/impl/mixin/ModelPartAccessor.class",
                "4ba21b44859748e48364c7df927b317c897b96710394c121f944c3887b17ab32");
        // Both redirects remain active through the shared attachment's original renderLeash call.
        com.radiance.api.render.ReviewedTransformations.register(
                "sable.mixins.json:entity.entity_leashing.EntityRendererMixin from mod sable",
                "dev/ryanhcode/sable/mixin/entity/entity_leashing/EntityRendererMixin.class",
                "38c0397f3a64ce29b59ae688637f2b66dc8d71c9bb0ab12786b1e3a5de9ab596");
        if (com.radiance.extra.compatibility.voxy.VoxyPresence.PRESENT)
            com.radiance.extra.compatibility.voxy.VoxyMixinBootstrap.register();
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        String family =
                mixinClassName.substring(mixinClassName.indexOf(".compatibility.") + 15)
                        .split("\\.")[0];
        String marker =
                switch (family) {
                    case "aeronautics" ->
                            "dev/eriksonn/aeronautics/content/blocks/levitite/LevititeShaderManager.class";
                    case "flywheel" -> "dev/engine_room/flywheel/api/internal/FlwApiLink.class";
                    case "sable" -> "dev/ryanhcode/sable/Sable.class";
                    case "simulated" ->
                            "dev/simulated_team/simulated/content/blocks/spring/SpringRenderer.class";
                    case "veil" -> "foundry/veil/Veil.class";
                    case "create" -> "com/simibubi/create/Create.class";
                    case "catnip", "ponder" -> "net/createmod/catnip/gui/UIRenderHelper.class";
                    default -> null;
                };
        if (marker != null && getClass().getClassLoader().getResource(marker) == null) return false;
        if (mixinClassName.contains(".compatibility.voxy."))
            return ENABLED && com.radiance.extra.compatibility.voxy.VoxyPresence.PRESENT;
        if (mixinClassName.contains(".compatibility.simulated.SimulatedGroup")
                || mixinClassName.endsWith(".SimulatedSimpleSubLevelGroupMixins")) {
            ClassLoader loader = getClass().getClassLoader();
            return ENABLED
                    && loader.getResource(
                                    "dev/simulated_team/simulated/util/SimpleSubLevelGroupRenderer.class")
                            != null
                    && loader.getResource("dev/ryanhcode/sable/Sable.class") != null
                    && loader.getResource("dev/engine_room/flywheel/api/internal/FlwApiLink.class")
                            != null;
        }
        if (mixinClassName.endsWith(".VeilWorldBlockLayersMixins")) {
            return ENABLED
                    && getClass()
                                    .getClassLoader()
                                    .getResource(
                                            "foundry/veil/ext/LevelRendererBlockLayerExtension.class")
                            != null;
        }
        if (mixinClassName.endsWith(".SableFlywheelEmbeddingMixins")) {
            ClassLoader loader = getClass().getClassLoader();
            return ENABLED
                    && loader.getResource(
                                    "dev/ryanhcode/sable/neoforge/mixinterface/compatibility/flywheel/EmbeddedEnvironmentExtension.class")
                            != null
                    && loader.getResource("dev/engine_room/flywheel/api/internal/FlwApiLink.class")
                            != null;
        }
        return ENABLED;
    }

    public void acceptTargets(Set<String> mine, Set<String> others) {}

    public List<String> getMixins() {
        return null;
    }

    public void preApply(String target, ClassNode node, String mixin, IMixinInfo info) {}

    public void postApply(String target, ClassNode node, String mixin, IMixinInfo info) {
        com.radiance.extra.compatibility.veil.VeilMixinCompatibility.postApply(target, node);
    }

    public String getRefMapperConfig() {
        return null;
    }
}
