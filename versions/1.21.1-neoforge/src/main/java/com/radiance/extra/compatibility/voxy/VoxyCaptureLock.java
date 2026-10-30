package com.radiance.extra.compatibility.voxy;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.WorldSection;

/**
 * Serializes source mutations with short array snapshots, never with mesh generation or GPU work.
 */
public final class VoxyCaptureLock {
    public static final ReentrantReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static volatile long revision;
    public static final long CONTENT_MASK = 0x00ffffffffffffffL;
    private static final Map<WorldSection, Long> versions =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Map<WorldSection, Boolean>> updates = new ThreadLocal<>();
    private static final ThreadLocal<Snapshot> snapshots = new ThreadLocal<>();

    public static final class Snapshot implements AutoCloseable {
        private final Map<WorldSection, Long> reads = new IdentityHashMap<>();
        private final Snapshot previous = snapshots.get();
        private boolean coherent = true;

        private Snapshot() {
            snapshots.set(this);
        }

        public boolean valid() {
            LOCK.readLock().lock();
            try {
                return coherent
                        && reads.entrySet().stream()
                                .allMatch(
                                        e ->
                                                e.getValue()
                                                        .equals(
                                                                versions.getOrDefault(
                                                                        e.getKey(), 0L)));
            } finally {
                LOCK.readLock().unlock();
            }
        }

        @Override
        public void close() {
            snapshots.set(previous);
        }
    }

    public static Snapshot snapshot() {
        return new Snapshot();
    }

    public static Map<WorldSection, Boolean> beginUpdate() {
        var old = updates.get();
        updates.set(new IdentityHashMap<>());
        return old;
    }

    public static void endUpdate(Map<WorldSection, Boolean> old) {
        if (old == null) updates.remove();
        else updates.set(old);
    }

    public static boolean relevant(WorldSection section, int flags) {
        var context = updates.get();
        // Child membership is independent of voxel/light changes. Unknown writers stay
        // conservative.
        return (flags & 2) != 0 || context == null || context.getOrDefault(section, true);
    }

    public static void contentChanged(WorldSection section) {
        changed();
        versions.put(section, revision);
    }

    public static void beforeInsert(VoxelizedSection incoming, WorldSection target) {
        int n = 16 >> target.lvl, mask = (1 << (target.lvl + 1)) - 1;
        int base =
                ((incoming.x & mask) * n)
                        | (((incoming.z & mask) * n) << 5)
                        | (((incoming.y & mask) * n) << 10);
        int start = VoxelizedSection.getBaseIndexForLevel(target.lvl);
        boolean changed =
                regionChanged(incoming.section, start, target._unsafeGetRawDataArray(), base, n);
        var context = updates.get();
        if (context != null) context.put(target, changed);
        if (changed) contentChanged(target);
    }

    static boolean regionChanged(long[] incoming, int start, long[] target, int base, int n) {
        for (int y = 0; y < n; y++)
            for (int z = 0; z < n; z++)
                for (int x = 0; x < n; x++)
                    if (((incoming[start + x + z * n + y * n * n]
                                            ^ target[base + x + z * 32 + y * 1024])
                                    & CONTENT_MASK)
                            != 0) return true;
        return false;
    }

    public static long[] copy(WorldSection section) {
        long[] result;
        LOCK.readLock().lock();
        try {
            long version = versions.getOrDefault(section, 0L);
            Snapshot snapshot = snapshots.get();
            if (snapshot != null) {
                Long old = snapshot.reads.put(section, version);
                if (old != null && old != version) snapshot.coherent = false;
            }
            result = section._unsafeGetRawDataArray().clone();
        } finally {
            LOCK.readLock().unlock();
        }
        // Received light is not a PT material property and must not split greedy rectangles.
        for (int i = 0; i < result.length; i++) result[i] &= CONTENT_MASK;
        return result;
    }

    private VoxyCaptureLock() {}

    public static long revision() {
        return revision;
    }

    public static void changed() {
        if (!LOCK.isWriteLockedByCurrentThread())
            throw new IllegalStateException("Unowned Voxy mutation");
        revision++;
    }

    public static long[] copy(long[] data) {
        LOCK.readLock().lock();
        try {
            return data.clone();
        } finally {
            LOCK.readLock().unlock();
        }
    }
}
