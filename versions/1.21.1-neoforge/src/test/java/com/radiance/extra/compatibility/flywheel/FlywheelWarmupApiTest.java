package com.radiance.extra.compatibility.flywheel;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;

class FlywheelWarmupApiTest {
    @Test
    void adapterLinkageDoesNotRequireAWorldOrGpuAllocation() {
        assertTrue(FlywheelModelWarmup.prepareClasses() > 8);
    }

    private ClassNode read(String name) throws IOException {
        try (var input =
                getClass()
                        .getClassLoader()
                        .getResourceAsStream("dev/engine_room/flywheel/" + name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            return node;
        }
    }

    @Test
    void installedFlywheelProvidesTheReloadAndPerWorkerWarmupBoundaries() throws IOException {
        var partial = read("lib/model/baked/PartialModel");
        assertTrue(
                partial.fields.stream()
                        .anyMatch(
                                f ->
                                        f.name.equals("ALL")
                                                && f.desc.equals(
                                                        "Ljava/util/concurrent/ConcurrentMap;")
                                                && (f.access & Opcodes.ACC_STATIC) != 0));
        var bufferer = read("lib/model/baked/BakedModelBuilder");
        assertTrue(
                bufferer.methods.stream()
                        .anyMatch(
                                f ->
                                        f.name.equals("build")
                                                && f.desc.equals(
                                                        "()Ldev/engine_room/flywheel/lib/model/SimpleModel;")
                                                && (f.access & Opcodes.ACC_PUBLIC) != 0));
        assertTrue(
                read("lib/util/RendererReloadCache").methods.stream()
                        .anyMatch(
                                m ->
                                        m.name.equals("onReloadLevelRenderer")
                                                && m.desc.equals("()V")));
        assertTrue(
                read("impl/task/ParallelTaskExecutor$WorkerThread").methods.stream()
                        .anyMatch(m -> m.name.equals("run") && m.desc.equals("()V")));
    }
}
