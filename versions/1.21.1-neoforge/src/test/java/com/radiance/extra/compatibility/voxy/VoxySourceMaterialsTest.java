package com.radiance.extra.compatibility.voxy;

import static org.junit.jupiter.api.Assertions.*;

import com.radiance.render.contract.RenderAbi;
import me.cortex.voxy.client.core.model.ModelLayer;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.*;

class VoxySourceMaterialsTest {
    @BeforeAll
    static void bootstrap() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.neoforged.fml.loading.LoadingModList.of(
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.List.of(),
                java.util.Map.of());
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test
    void sourceLayerDoesNotTurnEveryTranslucentProxyIntoGlass() {
        var water = VoxySourceMaterials.classify(1, false, ModelLayer.TRANSLUCENT, 0);
        var lava = VoxySourceMaterials.classify(2, false, ModelLayer.TRANSLUCENT, 15);
        var nonFluid = VoxySourceMaterials.classify(0, false, ModelLayer.TRANSLUCENT, 0);
        assertEquals(RenderAbi.ALPHA_TRANSMISSION, water.alpha());
        assertEquals(1, water.fluid());
        assertEquals(RenderAbi.ALPHA_OPAQUE, lava.alpha());
        assertEquals(2, lava.fluid());
        assertEquals(15, lava.emission());
        assertEquals(RenderAbi.ALPHA_COVERAGE, nonFluid.alpha());
        assertEquals(
                RenderAbi.ALPHA_TRANSMISSION,
                VoxySourceMaterials.classify(0, true, ModelLayer.TRANSLUCENT, 0).alpha());
        assertEquals(0, nonFluid.fluid());
        assertEquals(
                RenderAbi.ALPHA_CUTOUT_LOW,
                VoxySourceMaterials.classify(0, false, ModelLayer.CUTOUT, 0).alpha());
    }

    @Test
    void projectionEqualityAloneCannotEraseMaterialOrBiomeProviderIdentity() {
        Object pixels = new Object();
        var opaque = new VoxySourceMaterials.Material(0, RenderAbi.ALPHA_OPAQUE, 0);
        var glowing = new VoxySourceMaterials.Material(0, RenderAbi.ALPHA_OPAQUE, 15);
        var water = new VoxySourceMaterials.Material(1, RenderAbi.ALPHA_TRANSMISSION, 0);
        var ordinary = new VoxySourceMaterials.ProjectionKey(pixels, opaque, null);
        assertNotEquals(ordinary, new VoxySourceMaterials.ProjectionKey(pixels, glowing, null));
        assertNotEquals(ordinary, new VoxySourceMaterials.ProjectionKey(pixels, water, null));
        assertEquals(ordinary, new VoxySourceMaterials.ProjectionKey(pixels, opaque, null));
        assertNotEquals(
                new VoxySourceMaterials.ProjectionKey(
                        pixels, opaque, Blocks.GRASS_BLOCK.defaultBlockState()),
                new VoxySourceMaterials.ProjectionKey(
                        pixels, opaque, Blocks.OAK_LEAVES.defaultBlockState()));
    }
}
