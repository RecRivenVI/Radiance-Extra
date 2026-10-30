package com.radiance.extra.compatibility.simulated;

import static com.radiance.client.vertex.PBRVertexFormatElements.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.*;
import com.radiance.client.vertex.*;
import java.nio.*;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.*;

class SpringProviderPolicyTest {
    private static RenderType layer;

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
        layer =
                new RenderType(
                        "test_spring_contract",
                        PBRVertexFormats.PBR_TRIANGLE,
                        VertexFormat.Mode.QUADS,
                        256,
                        false,
                        false,
                        () -> {},
                        () -> {}) {};
    }

    @Test
    void providerInheritedEmissionCannotCrashOrFreezeSpringFactoryDraws() {
        Object testOwner = new Object(), testModel = new Object();
        SpringShaderVerification.verified(testModel);
        SpringShaderVerification.attached(testOwner, testModel);
        try {
            float first = providerSpringPosition(1.0F);
            float next = providerSpringPosition(2.5F);
            assertEquals(1.0F, first);
            assertEquals(2.5F, next);
        } finally {
            SpringShaderVerification.released(testOwner, testModel);
        }
    }

    private static float providerSpringPosition(float x) {
        StorageVertexConsumerProvider provider =
                new StorageVertexConsumerProvider(1024, 1.0F) {
                    @Override
                    protected VertexConsumer createQuadConsumer(
                            ByteBufferBuilder allocator, RenderType renderLayer) {
                        return SpringWorldLowering.createConsumer(
                                allocator,
                                new SpringDrawContract.Draw(
                                        renderLayer,
                                        ResourceLocation.fromNamespaceAndPath(
                                                "simulated", "textures/block/spring/spring.png")));
                    }
                };
        try {
            PBRVertexConsumer spring = (PBRVertexConsumer) provider.getBuffer(layer);
            spring.addVertex(x, 0, 0).setColor(10, 20, 30, 40).setNormal(0, 0, 1);
            spring.addVertex(x + 1, 0, 0).setColor(10, 20, 30, 40).setNormal(0, 0, 1);
            spring.addVertex(x + 1, 1, 0).setColor(10, 20, 30, 40).setNormal(0, 0, 1);
            spring.addVertex(x, 1, 0).setColor(10, 20, 30, 40).setNormal(0, 0, 1);
            try (MeshData mesh = spring.end()) {
                ByteBuffer data = mesh.vertexBuffer().duplicate().order(ByteOrder.nativeOrder());
                assertEquals(0.0F, data.getFloat(offset(PBR_ALBEDO_EMISSION)));
                assertEquals(
                        com.radiance.render.contract.SourceAlpha.encode(
                                0, com.radiance.render.contract.RenderAbi.SOURCE_ALPHA_TEXEL_LOW),
                        data.getInt(offset(PBR_POST_BASE) + 12));
                assertEquals(2, data.getInt(offset(PBR_USE_NORM)));
                return data.getFloat(0);
            }
        } finally {
            provider.close();
        }
    }

    private static int offset(com.mojang.blaze3d.vertex.VertexFormatElement element) {
        return PBRVertexFormats.PBR_TRIANGLE.getOffsetsByElement()[element.id()];
    }
}
