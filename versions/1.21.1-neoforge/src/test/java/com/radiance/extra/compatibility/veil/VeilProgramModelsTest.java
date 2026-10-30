package com.radiance.extra.compatibility.veil;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class VeilProgramModelsTest {
    @Test
    void uncompiledProgramDeclinesDrawWithoutInventingUniformState() {
        var source =
                (foundry.veil.api.client.render.shader.program.ShaderProgram)
                        java.lang.reflect.Proxy.newProxyInstance(
                                getClass().getClassLoader(),
                                new Class<?>[] {
                                    foundry.veil.api.client.render.shader.program.ShaderProgram
                                            .class
                                },
                                (proxy, method, args) ->
                                        switch (method.getName()) {
                                            case "getName" ->
                                                    net.minecraft.resources.ResourceLocation
                                                            .fromNamespaceAndPath(
                                                                    "probe", "unsupported");
                                            case "hashCode" -> System.identityHashCode(proxy);
                                            case "equals" -> proxy == args[0];
                                            default -> throw new AssertionError(method.getName());
                                        });
        assertFalse(VeilShaderBridge.hasProgram(source));
        assertThrows(
                com.radiance.api.render.UnsupportedDraw.class,
                () -> VeilShaderBridge.metadata(source, null));
        assertDoesNotThrow(() -> VeilShaderAdapter.setDefaultUniforms(source, null, null, null));
        VeilShaderBridge.bindProgram(source);
        try {
            assertSame(source, VeilShaderBridge.currentProgram());
        } finally {
            VeilShaderBridge.unbindProgram();
        }
    }

    @Test
    void equalNumericIdsAndEqualRecordsKeepIndependentCompiledOwners() {
        record Owner(int nativeId) {}
        var first = new Owner(7);
        var second = new Owner(7);
        assertEquals(first, second);
        var models = new VeilProgramModels<Object>();
        Object a = new Object(), b = new Object(), cache = new Object();
        models.put(first, a);
        models.put(cache, a);
        models.put(second, b);
        assertSame(a, models.get(first));
        assertSame(b, models.get(second));
        models.release(first);
        models.release(cache);
        assertNull(models.get(first));
        assertSame(b, models.get(second));
        models.release(second);
        assertNull(models.get(second));
    }
}
