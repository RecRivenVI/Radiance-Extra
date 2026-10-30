package com.radiance.extra.compatibility.flywheel;

import com.radiance.extra.mixins.compatibility.flywheel.FlywheelPartialModelsAccessor;
import dev.engine_room.flywheel.impl.task.FlwTaskExecutor;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.Minecraft;

/** Uses Flywheel's own reload cache; never creates placeholder or delayed visuals. */
public final class FlywheelModelWarmup {
    private static final System.Logger LOG = System.getLogger("Radiance Extra/Flywheel");
    private static final AtomicInteger preparedWorkers = new AtomicInteger();

    private FlywheelModelWarmup() {}

    public static void prepareModels() {
        if (!RadianceFlywheelBackend.nativeSupported()) return;
        long start = System.nanoTime();
        prepareClasses();
        int count = 0;
        long vertices = 0;
        var partials = List.copyOf(FlywheelPartialModelsAccessor.radiance$all().values());
        for (PartialModel partial :
                partials.stream()
                        .sorted(Comparator.comparing(p -> p.modelLocation().toString()))
                        .toList()) {
            // RegisterAdditional may contain a late or missing resource. Do not fabricate a model.
            if (partial.get() == null) continue;
            var model = Models.partial(partial);
            for (var mesh : model.meshes()) vertices += mesh.mesh().vertexCount();
            count++;
        }
        // Start the existing pool while loading; each actual worker prepares its thread-local
        // emitter state before accepting any visual task. Serial execution keeps its normal path.
        FlwTaskExecutor.get();
        LOG.log(
                System.Logger.Level.INFO,
                "Prepared {0} partial models ({1} vertices) in {2} ms after cache reload; worker caches ready={3}",
                count,
                vertices,
                (System.nanoTime() - start) / 1_000_000.0,
                preparedWorkers.get());
    }

    public static void prepareWorker() {
        if (!RadianceFlywheelBackend.nativeSupported()) return;
        // The public builder prepares this worker's emitters with a disposable cube.
        // Its temporary mesh has Flywheel's normal managed-memory lifetime; no instance is created.
        new BakedModelBuilder(Minecraft.getInstance().getModelManager().getMissingModel()).build();
        preparedWorkers.incrementAndGet();
    }

    static int prepareClasses() {
        int count = 0;
        for (Class<?> type :
                new Class<?>[] {
                    RadianceFlywheelEngine.class,
                    FlywheelInstanceAdapter.class,
                    FlywheelInstanceDecoder.class,
                    FlywheelMeshData.class,
                    FlywheelMaterialData.class
                }) count += prepareClass(type);
        // Visual section collectors use these fixed shapes on their first construction.
        LongSet.of();
        LongSet.of(0L);
        LongSets.unmodifiable(LongSet.of(0L, 1L));
        return count;
    }

    private static int prepareClass(Class<?> type) {
        try {
            Class.forName(type.getName(), true, type.getClassLoader());
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException(
                    "Cannot prepare loaded adapter class " + type.getName(), failure);
        }
        int count = 1;
        for (Class<?> nested : type.getDeclaredClasses()) count += prepareClass(nested);
        return count;
    }
}
