package com.radiance.extra.compatibility.voxy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import me.cortex.voxy.client.VoxyClientInstance;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.commonImpl.VoxyInstance;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** One bounded CPU mesher and an immutable, complete-child LOD cut for a leased Voxy world. */
final class VoxyPtSession implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger("Radiance/Voxy");
    private static final int MAX_NODES = 8192;
    private final long epoch;
    private com.radiance.api.render.LodScene scene;
    private final Level level;
    private final VoxyClientInstance owner;
    private final IVoxyRenderSystemHolder holder;
    private final VoxyInstance.WorldLease lease;
    private final WorldEngine world;
    private final VoxyModels models;
    private final java.util.concurrent.locks.ReentrantLock modelLock =
            new java.util.concurrent.locks.ReentrantLock();
    private volatile boolean modelWorkRequested;
    private final ArrayList<Result> waitingUpload = new ArrayList<>();
    private final AtomicLong generated = new AtomicLong(),
            superseded = new AtomicLong(),
            lightOnly = new AtomicLong();
    private long nativeRejected;
    private final RenderDataFactory mesher;
    private final ConcurrentHashMap<Long, Node> nodes = new ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentLinkedDeque<Node> work =
            new java.util.concurrent.ConcurrentLinkedDeque<>();
    private final ArrayBlockingQueue<Result> completed = new ArrayBlockingQueue<>(4);
    private final Set<Long> absent = ConcurrentHashMap.newKeySet();
    private final Set<Integer> missingModels = ConcurrentHashMap.newKeySet();
    private final Set<Node> blockedModels = ConcurrentHashMap.newKeySet();
    private final Thread worker;
    private volatile boolean running = true;
    private volatile Throwable workerFailure;
    private int biomeCount;
    private long frames;
    private final AtomicLong generations = new AtomicLong();
    private final AtomicLong planningChanges = new AtomicLong();
    private final AtomicLong deferredQueued = new AtomicLong();
    private volatile long completedPlan;
    private long planEpoch, planningRuns, planningSkipped;
    private PlanningInput lastPlan;
    private com.radiance.api.render.LodScene.Coverage nearCoverage;
    private Set<Long> previousCut = Set.of();
    private VoxyRootCoverage previousRoots;
    private boolean budgetReported;
    private boolean nativeBudgetReported;

    private record PlanningInput(
            double x,
            double y,
            double z,
            double distance,
            double pixels,
            double threshold,
            long content,
            long coverage) {}

    private static final class Node {
        final long key;
        final AtomicLong revision;
        final AtomicBoolean queued = new AtomicBoolean();
        final AtomicBoolean requested = new AtomicBoolean();
        volatile boolean ready;
        volatile int children;
        long lastUsed;
        volatile long activePlan;

        Node(long key, long generation) {
            this.key = key;
            revision = new AtomicLong(generation);
        }
    }

    private static final class Result implements AutoCloseable {
        final Node node;
        final long revision;
        final BuiltSection mesh;
        java.nio.ByteBuffer canonical;
        long rejectedResidents = -1;

        Result(Node node, long revision, BuiltSection mesh) {
            this.node = node;
            this.revision = revision;
            this.mesh = mesh;
        }

        long bytes() {
            return mesh.isEmpty() ? 0 : mesh.geometryBuffer.size / 8 * 104;
        }

        @Override
        public void close() {
            if (canonical != null) org.lwjgl.system.MemoryUtil.memFree(canonical);
            mesh.free();
        }
    }

    VoxyPtSession(
            long epoch,
            Level level,
            VoxyClientInstance owner,
            IVoxyRenderSystemHolder holder,
            VoxyInstance.WorldLease lease) {
        this.epoch = epoch;
        this.level = level;
        this.owner = owner;
        this.holder = holder;
        this.lease = lease;
        world = lease.engine();
        ModelFactory factory = null;
        RenderDataFactory created = null;
        VoxyModels createdModels = null;
        boolean nativeStarted = false;
        try {
            factory = new ModelFactory(world.getMapper(), null);
            scene = new com.radiance.api.render.LodScene(epoch);
            nativeStarted = true;
            models = new VoxyModels(scene, factory);
            createdModels = models;
            created = new RenderDataFactory(world, factory, false, false);
            mesher = created;
            VoxyCaptureLock.LOCK.writeLock().lock();
            try {
                world.setDirtyCallback(
                        (section, flags, neighbours) -> {
                            if (!VoxyCaptureLock.relevant(section, flags)) {
                                lightOnly.incrementAndGet();
                                return;
                            }
                            dirty(section.key);
                            for (int side = 0; side < 6; side++) {
                                if ((neighbours & (1 << side)) == 0) continue;
                                int x = section.x, y = section.y, z = section.z;
                                int delta = (side & 1) == 0 ? -1 : 1;
                                if (side < 2) y += delta;
                                else if (side < 4) x += delta;
                                else z += delta;
                                dirty(WorldEngine.getWorldSectionId(section.lvl, x, y, z));
                            }
                        });
            } finally {
                VoxyCaptureLock.LOCK.writeLock().unlock();
            }
            worker = new Thread(this::run, "Radiance Voxy mesh");
            worker.setDaemon(true);
            worker.start();
            LOGGER.info(
                    "Voxy PT session {} acquired source world lease; CPU mesher=1, GL renderer=0",
                    epoch);
        } catch (RuntimeException | Error e) {
            if (nativeStarted) {
                VoxyCaptureLock.LOCK.writeLock().lock();
                try {
                    world.setDirtyCallback(null);
                } finally {
                    VoxyCaptureLock.LOCK.writeLock().unlock();
                }
                try {
                    scene.close();
                } catch (RuntimeException | Error cleanup) {
                    e.addSuppressed(cleanup);
                }
            }
            try {
                if (created != null) created.free();
            } catch (RuntimeException | Error cleanup) {
                e.addSuppressed(cleanup);
            }
            try {
                if (createdModels != null) createdModels.close();
                else if (factory != null) factory.free();
            } catch (RuntimeException | Error cleanup) {
                e.addSuppressed(cleanup);
            }
            try {
                lease.close();
            } catch (RuntimeException | Error cleanup) {
                e.addSuppressed(cleanup);
            }
            throw e;
        }
    }

    private void queue(Node node) {
        queue(node, false);
    }

    private void queue(Node node, boolean update) {
        node.requested.set(true);
        if (running && nodes.get(node.key) == node && node.queued.compareAndSet(false, true)) {
            if (update) work.addFirst(node);
            else work.addLast(node);
        }
    }

    private void dirty(long key) {
        absent.remove(key);
        Node node = nodes.get(key);
        if (node != null) {
            node.revision.accumulateAndGet(generations.incrementAndGet(), Math::max);
            queue(node, true);
        }
        planningChanges.incrementAndGet();
    }

    private Node request(long key) {
        if (absent.contains(key)) return null;
        Node present = nodes.get(key);
        if (present != null) {
            present.lastUsed = frames;
            present.activePlan = planEpoch;
            if (present.requested.get() && !present.queued.get()) queue(present);
            return present;
        }
        if (nodes.size() >= MAX_NODES) {
            if (!budgetReported) {
                LOGGER.warn(
                        "Voxy PT residency budget reached ({} nodes); retaining valid parent proxies",
                        MAX_NODES);
                budgetReported = true;
            }
            return null;
        }
        Node next = new Node(key, generations.incrementAndGet());
        next.lastUsed = frames;
        next.activePlan = planEpoch;
        Node old = nodes.putIfAbsent(key, next);
        if (old != null) {
            old.lastUsed = frames;
            old.activePlan = planEpoch;
            return old;
        }
        queue(next);
        return next;
    }

    private void finish(Node node) {
        node.queued.set(false);
        if (node.requested.get()) queue(node);
    }

    private void run() {
        try {
            while (running) {
                if (modelWorkRequested) {
                    Thread.sleep(1);
                    continue;
                }
                Node node = work.poll();
                if (node == null) {
                    Thread.sleep(10);
                    continue;
                }
                if (nodes.get(node.key) != node) {
                    node.queued.set(false);
                    continue;
                }
                long publishedPlan = completedPlan;
                if (node.activePlan < publishedPlan) {
                    // Publish only finished plans: a traversal still being assembled must
                    // not cancel work it has not reached yet. Preserve dirty intent for
                    // a later request; do not rebuild inactive ancestors/children now.
                    node.requested.set(true);
                    node.queued.set(false);
                    deferredQueued.incrementAndGet();
                    planningChanges.incrementAndGet();
                    continue;
                }
                node.requested.set(false);
                long revision = node.revision.get();
                BuiltSection mesh = null;
                boolean delivered = false;
                try (var snapshot = VoxyCaptureLock.snapshot()) {
                    var section = world.acquireIfExists(node.key);
                    if (section == null) {
                        // Unknown requests are not resident meshes. Never retire a valid old
                        // version when storage is temporarily unavailable.
                        VoxyCaptureLock.LOCK.readLock().lock();
                        try {
                            if (!node.ready
                                    && revision == node.revision.get()
                                    && absent.size() < 65536) {
                                absent.add(node.key);
                                if (nodes.remove(node.key, node)) planningChanges.incrementAndGet();
                            }
                        } finally {
                            VoxyCaptureLock.LOCK.readLock().unlock();
                        }
                        continue; // Unknown storage data is never published as empty.
                    }
                    try {
                        modelLock.lockInterruptibly();
                        try {
                            mesh = mesher.generateMesh(section);
                            generated.incrementAndGet();
                        } finally {
                            modelLock.unlock();
                        }
                    } finally {
                        section.release();
                    }
                    if (!snapshot.valid() || revision != node.revision.get()) {
                        superseded.incrementAndGet();
                        mesh.free();
                        mesh = null;
                        node.requested.set(true);
                        continue;
                    }
                    Result result = new Result(node, revision, mesh);
                    while (running) {
                        if (completed.offer(result, 100, TimeUnit.MILLISECONDS)) {
                            mesh = null;
                            delivered = true;
                            break;
                        }
                    }
                } catch (IdNotYetComputedException missing) {
                    blockedModels.add(node);
                    if (missing.isIdBlockId) missingModels.add(missing.id);
                    else if (missingModels.isEmpty())
                        throw new IllegalStateException(
                                "Voxy fluid model dependency was not scheduled", missing);
                } finally {
                    if (mesh != null) mesh.free();
                    if (revision != node.revision.get()) node.requested.set(true);
                    if (!delivered) finish(node);
                }
            }
        } catch (InterruptedException e) {
            if (running) workerFailure = e;
            Thread.currentThread().interrupt();
        } catch (Throwable e) {
            workerFailure = e;
        }
    }

    void frame() {
        if (workerFailure != null)
            throw new IllegalStateException("Voxy mesh worker failed", workerFailure);
        modelWorkRequested |= !missingModels.isEmpty() || frames % 30 == 0;
        if (modelWorkRequested && modelLock.tryLock()) {
            try {
                var biomes = world.getMapper().getBiomeEntries();
                boolean newBiomes = biomeCount != biomes.length;
                for (; biomeCount < biomes.length; biomeCount++)
                    models.factory.addBiome(biomes[biomeCount]);
                var baked = new ArrayList<Integer>();
                for (int id : missingModels) {
                    models.factory.addEntry(id);
                    baked.add(id);
                    if (baked.size() == 2) break;
                }
                if (!baked.isEmpty() || newBiomes || frames == 0) {
                    models.factory.processAllThings();
                    models.drain();
                    for (int id : baked) {
                        if (models.factory.hasModelForBlockId(id)) {
                            missingModels.remove(id);
                        }
                    }
                    models.publish();
                    // Retry only on model progress, not every render frame.
                    for (Node node : blockedModels.toArray(Node[]::new)) {
                        if (blockedModels.remove(node)) queue(node);
                    }
                }
            } finally {
                modelWorkRequested = false;
                modelLock.unlock();
            }
        }
        long remainingUpload = 8L * 1024 * 1024;
        long[] nativeState = scene.counters();
        long nativeResidents = nativeState[0];
        long oldCoverage = nearCoverage == null ? 0 : nearCoverage.revision();
        if (nativeState.length > 9 && nativeState[9] == 0) nearCoverage = null;
        else if (nativeState.length > 9 && nativeState[9] != oldCoverage) {
            var coverage = scene.coverage(oldCoverage);
            if (coverage != null) nearCoverage = coverage;
        }
        for (int n = 0; n < 4 && waitingUpload.size() < 4; n++) {
            Result result = completed.poll();
            if (result == null) break;
            waitingUpload.add(result);
        }
        for (var iterator = waitingUpload.iterator(); iterator.hasNext(); ) {
            Result result = iterator.next();
            boolean done = false;
            try {
                if (nodes.get(result.node.key) != result.node
                        || result.revision != result.node.revision.get()) {
                    superseded.incrementAndGet();
                    result.node.requested.set(true);
                    done = true;
                    continue;
                }
                if (result.rejectedResidents >= 0 && nativeResidents >= result.rejectedResidents)
                    continue;
                if (remainingUpload <= 0) break;
                validate(result.mesh);
                if (result.canonical == null)
                    result.canonical =
                            VoxyGeometry.quads(
                                    result.mesh.isEmpty() ? 0 : result.mesh.geometryBuffer.address,
                                    result.mesh.isEmpty()
                                            ? 0
                                            : Math.toIntExact(result.mesh.geometryBuffer.size / 8));
                if (!scene.section(
                        VoxyGeometry.key(result.mesh.position),
                        result.revision,
                        result.canonical)) {
                    // Native admission is limited by resident slots, not geometry bytes.
                    // A larger replacement must not prevent retry after a slot was released.
                    result.rejectedResidents = nativeResidents;
                    nativeRejected++;
                    if (!nativeBudgetReported) {
                        LOGGER.warn(
                                "Voxy PT budget reached; retaining completed CPU meshes and valid old proxies");
                        nativeBudgetReported = true;
                    }
                    continue;
                }
                remainingUpload -= result.bytes();
                nativeResidents = scene.counters()[0];
                int children = Byte.toUnsignedInt(result.mesh.childExistence);
                if (!result.node.ready || result.node.children != children)
                    planningChanges.incrementAndGet();
                result.node.ready = true;
                result.node.children = children;
                done = true;
            } finally {
                if (done) {
                    iterator.remove();
                    result.close();
                    finish(result.node);
                }
            }
        }
        var minecraft = Minecraft.getInstance();
        var camera = minecraft.gameRenderer.getMainCamera().getPosition();
        double distance = VoxyConfig.CONFIG.sectionRenderDistance * 512.0;
        double pixels =
                minecraft.getWindow().getHeight()
                        / (2 * Math.tan(Math.toRadians(minecraft.options.fov().get()) * 0.5));
        double threshold = VoxyConfig.CONFIG.subDivisionSize;
        long content = planningChanges.get();
        long coverageVersion = nearCoverage == null ? 0 : nearCoverage.revision();
        if (lastPlan == null
                || lastPlan.x != camera.x
                || lastPlan.y != camera.y
                || lastPlan.z != camera.z
                || lastPlan.distance != distance
                || lastPlan.pixels != pixels
                || lastPlan.threshold != threshold
                || lastPlan.content != content
                || lastPlan.coverage != coverageVersion) {
            planEpoch++;
            planningRuns++;
            Set<Long> cut = new HashSet<>();
            var roots =
                    VoxyRootCoverage.create(
                            camera.x,
                            camera.z,
                            distance,
                            level.getMinBuildHeight(),
                            level.getMaxBuildHeight());
            roots.visitNearFirst(
                    camera.x,
                    camera.z,
                    (x, z) -> {
                        for (int y = roots.minY; y <= roots.maxY; y++) {
                            choose(4, x, y, z, camera.x, camera.y, camera.z, pixels, cut);
                        }
                    });
            if (!roots.equals(previousRoots))
                absent.removeIf(
                        key -> {
                            int shift = 4 - WorldEngine.getLevel(key);
                            return !roots.containsColumn(
                                            WorldEngine.getX(key) >> shift,
                                            WorldEngine.getZ(key) >> shift)
                                    || (WorldEngine.getY(key) >> shift) < roots.minY
                                    || (WorldEngine.getY(key) >> shift) > roots.maxY;
                        });
            if (!cut.equals(previousCut) || !roots.equals(previousRoots)) {
                if (previousRoots == null)
                    LOGGER.info(
                            "Voxy PT requested domain: roots={} rows={} residentBudget={}",
                            roots.rootCount(),
                            roots.rows().length / 3,
                            MAX_NODES);
                scene.select(
                        VoxyGeometry.keys(cut.stream().mapToLong(Long::longValue).toArray()),
                        roots.rows(),
                        roots.minY,
                        roots.maxY);
                previousCut = Set.copyOf(cut);
                previousRoots = roots;
            }
            // Keep the version from before traversal: a concurrent source notification must
            // schedule another plan, even if it arrived while this traversal was running.
            lastPlan =
                    new PlanningInput(
                            camera.x,
                            camera.y,
                            camera.z,
                            distance,
                            pixels,
                            threshold,
                            content,
                            coverageVersion);
            completedPlan = planEpoch;
        } else planningSkipped++;
        if (frames % 60 == 0
                && nodes.values()
                        .removeIf(
                                node ->
                                        node.activePlan != planEpoch
                                                && frames - node.lastUsed > 60
                                                && !previousCut.contains(node.key))) {
            scene.retain(
                    VoxyGeometry.keys(
                            nodes.keySet().stream().mapToLong(Long::longValue).toArray()));
            budgetReported = false;
            nativeBudgetReported = false;
            planningChanges.incrementAndGet();
        }
        if ((frames++ % 300) == 0)
            LOGGER.info(
                    "Voxy PT session {} counters {} work generated={} superseded={} lightOnly={} nativeRejected={} retainedResults={} planningRuns={} planningSkipped={}",
                    epoch,
                    counters(),
                    generated.get(),
                    superseded.get(),
                    lightOnly.get(),
                    nativeRejected,
                    waitingUpload.size() + completed.size(),
                    planningRuns,
                    planningSkipped);
    }

    private void choose(
            int lod,
            int x,
            int y,
            int z,
            double cx,
            double cy,
            double cz,
            double pixels,
            Set<Long> cut) {
        Node node = request(WorldEngine.getWorldSectionId(lod, x, y, z));
        if (node == null || !node.ready) return;
        int width = 32 << lod;
        int sections = 2 << lod;
        if (nearCoverage != null
                && nearCoverage.coversSections(
                        Math.multiplyExact(x, sections),
                        Math.multiplyExact(y, sections),
                        Math.multiplyExact(z, sections),
                        sections)) {
            // Keep the coarse owner available for handoff, but do not manufacture
            // its fine descendants behind complete primary near geometry.
            cut.add(node.key);
            return;
        }
        double dx = Math.max(0, Math.abs(x * (double) width + width * 0.5 - cx) - width * 0.5);
        double dy = Math.max(0, Math.abs(y * (double) width + width * 0.5 - cy) - width * 0.5);
        double dz = Math.max(0, Math.abs(z * (double) width + width * 0.5 - cz) - width * 0.5);
        boolean refine =
                lod != 0
                        && node.children != 0
                        && width * pixels / Math.max(1, Math.sqrt(dx * dx + dy * dy + dz * dz))
                                > VoxyConfig.CONFIG.subDivisionSize;
        if (refine) {
            boolean ready = true;
            for (int i = 0; i < 8; i++) {
                if ((node.children & (1 << i)) == 0) continue;
                Node child =
                        request(
                                WorldEngine.getWorldSectionId(
                                        lod - 1,
                                        x * 2 + (i & 1),
                                        y * 2 + ((i >> 2) & 1),
                                        z * 2 + ((i >> 1) & 1)));
                ready &= child != null && child.ready;
            }
            if (ready) {
                for (int i = 0; i < 8; i++)
                    if ((node.children & (1 << i)) != 0)
                        choose(
                                lod - 1,
                                x * 2 + (i & 1),
                                y * 2 + ((i >> 2) & 1),
                                z * 2 + ((i >> 1) & 1),
                                cx,
                                cy,
                                cz,
                                pixels,
                                cut);
                return;
            }
        }
        cut.add(node.key);
    }

    private static void validate(BuiltSection mesh) {
        if (mesh.isEmpty()) return;
        if ((mesh.geometryBuffer.size & 7) != 0
                || mesh.geometryBuffer.size > 4L * 1024 * 1024
                || mesh.offsets == null
                || mesh.offsets.length != 8
                || mesh.offsets[0] != 0)
            throw new IllegalArgumentException("Voxy BuiltSection ABI/budget mismatch");
        long count = mesh.geometryBuffer.size / 8;
        int previous = 0;
        for (int offset : mesh.offsets) {
            if (offset < previous || offset > count)
                throw new IllegalArgumentException("Voxy quad group range");
            previous = offset;
        }
    }

    long[] counters() {
        long[] nativeCounters = scene.counters();
        long[] result = java.util.Arrays.copyOf(nativeCounters, 21);
        // Preserve the existing source counters; append the effective PT cut diagnostics.
        result[18] = nativeCounters.length > 7 ? nativeCounters[7] : -1;
        result[19] = nativeCounters.length > 8 ? nativeCounters[8] : -1;
        result[20] = deferredQueued.get();
        result[7] = generated.get();
        result[8] = superseded.get();
        result[9] = lightOnly.get();
        result[10] = nativeRejected;
        result[11] = waitingUpload.size() + completed.size();
        System.arraycopy(models.summary(), 0, result, 12, 6);
        return result;
    }

    long epoch() {
        return epoch;
    }

    @Override
    public void close() {
        running = false;
        VoxyCaptureLock.LOCK.writeLock().lock();
        try {
            world.setDirtyCallback(null);
        } finally {
            VoxyCaptureLock.LOCK.writeLock().unlock();
        }
        boolean interrupted = false;
        while (worker.isAlive()) {
            try {
                worker.join(100);
            } catch (InterruptedException e) {
                interrupted = true;
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Result result;
        while ((result = completed.poll()) != null) result.close();
        for (Result pending : waitingUpload) pending.close();
        waitingUpload.clear();
        try {
            scene.close();
        } finally {
            try {
                mesher.free();
                models.close();
            } finally {
                lease.close();
                owner.unregisterRenderer(holder);
            }
        }
        LOGGER.info(
                "Voxy PT session {} released; worker stopped and source world lease returned",
                epoch);
    }
}
