package com.radiance.extra.compatibility.ponder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Supplemental wiring guard; actual rendering is exercised by the isolated Catnip smoke. */
class PonderDefaultContractTest {
    @Test
    void reviewedRenderTypeMixinOnlyExposesTheOriginalFactory() throws Exception {
        var path = "/net/createmod/ponder/mixin/client/accessor/RenderTypeAccessor.class";
        try (var stream = getClass().getResourceAsStream(path)) {
            assertNotNull(stream);
            byte[] bytes = stream.readAllBytes();
            var node = new org.objectweb.asm.tree.ClassNode();
            new org.objectweb.asm.ClassReader(bytes).accept(node, 0);
            assertTrue(node.fields.isEmpty());
            assertEquals(1, node.methods.size());
            var method = node.methods.getFirst();
            assertTrue((method.access & org.objectweb.asm.Opcodes.ACC_STATIC) != 0);
            assertEquals("catnip$create", method.name);
            assertEquals(1, method.visibleAnnotations.size());
            var invoker = method.visibleAnnotations.getFirst();
            assertEquals("Lorg/spongepowered/asm/mixin/gen/Invoker;", invoker.desc);
            assertEquals(java.util.List.of("value", "create"), invoker.values);
            String hash =
                    java.util.HexFormat.of()
                            .formatHex(
                                    java.security.MessageDigest.getInstance("SHA-256")
                                            .digest(bytes));
            try (var plugin =
                    getClass()
                            .getResourceAsStream(
                                    "/com/radiance/extra/mixin/ExtraMixinPlugin.class")) {
                assertNotNull(plugin);
                var registration = new org.objectweb.asm.tree.ClassNode();
                new org.objectweb.asm.ClassReader(plugin).accept(registration, 0);
                var onLoad =
                        registration.methods.stream()
                                .filter(m -> m.name.equals("onLoad"))
                                .findFirst()
                                .orElseThrow();
                var constants = new java.util.ArrayList<Object>();
                for (var instruction : onLoad.instructions)
                    if (instruction instanceof org.objectweb.asm.tree.LdcInsnNode value)
                        constants.add(value.cst);
                int resource = constants.indexOf(path.substring(1));
                assertTrue(resource > 0, "Actual source Mixin must be registered");
                assertEquals(
                        hash,
                        constants.get(resource + 1),
                        "Only reviewed bytes may bypass capture");
            }
        }
    }

    @Test
    void activeConfigurationCannotInstallArchivedPtHooks() throws Exception {
        try (var stream = getClass().getResourceAsStream("/radiance_extra.mixins.json")) {
            assertNotNull(stream);
            var client =
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8))
                            .getAsJsonObject()
                            .getAsJsonArray("client");
            var names = new java.util.HashSet<String>();
            client.forEach(value -> names.add(value.getAsString()));
            assertFalse(names.contains("compatibility.ponder.PonderScenePathTracingMixins"));
            assertFalse(names.contains("compatibility.ponder.PonderUILifecycleMixins"));
            assertTrue(names.contains("compatibility.catnip.CatnipRasterScreenMixins"));
            assertTrue(names.contains("compatibility.ponder.PonderUIRenderHelperMixins"));
        }
    }
}
