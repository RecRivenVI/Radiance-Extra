package com.radiance.extra.compatibility.aeronautics;

import com.radiance.api.render.ComputedSurface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.lwjgl.system.MemoryUtil;

/**
 * Immutable cell/shape snapshots. No voxel-shape queries occur at individual tessellation points.
 */
public final class LevititeContacts implements AutoCloseable {
    private static final class Version {
        final AtomicLong value = new AtomicLong();
        int users;
    }

    private static final ConcurrentHashMap<Long, Version> VERSIONS = new ConcurrentHashMap<>();
    private Grid plot, world;
    private ComputedSurface data;
    private static long builds;

    public static void invalidate(BlockPos origin) {
        var watched = VERSIONS.get(SectionPos.asLong(origin));
        if (watched != null) watched.value.incrementAndGet();
    }

    public static void reset() {
        VERSIONS.clear();
    }

    public static long builds() {
        return builds;
    }

    private static final class Grid {
        final AABB bounds;
        final int x, y, z, nx, ny, nz;
        final long[] sections, versions;
        final Version[] watches;
        final int[] ranges;
        final ArrayList<AABB> boxes = new ArrayList<>();

        Grid(ClientLevel level, AABB requested) {
            x = (int) Math.floor(requested.minX) - 3;
            y = (int) Math.floor(requested.minY) - 3;
            z = (int) Math.floor(requested.minZ) - 3;
            nx = (int) Math.ceil(requested.maxX) + 3 - x;
            ny = (int) Math.ceil(requested.maxY) + 3 - y;
            nz = (int) Math.ceil(requested.maxZ) + 3 - z;
            int cells = Math.multiplyExact(Math.multiplyExact(nx, ny), nz);
            // Source sections are bounded. Refuse pathological poses before allocating a giant
            // grid.
            if (cells <= 0 || cells > 2_000_000)
                throw new IllegalStateException("Levitite contact extent");
            bounds = new AABB(x + 1, y + 1, z + 1, x + nx - 1, y + ny - 1, z + nz - 1);
            ranges = new int[cells * 2];
            int sx0 = x >> 4,
                    sy0 = y >> 4,
                    sz0 = z >> 4,
                    sx1 = (x + nx - 1) >> 4,
                    sy1 = (y + ny - 1) >> 4,
                    sz1 = (z + nz - 1) >> 4;
            int count = (sx1 - sx0 + 1) * (sy1 - sy0 + 1) * (sz1 - sz0 + 1);
            sections = new long[count];
            versions = new long[count];
            watches = new Version[count];
            int at = 0;
            for (int sz = sz0; sz <= sz1; sz++)
                for (int sy = sy0; sy <= sy1; sy++)
                    for (int sx = sx0; sx <= sx1; sx++) {
                        sections[at] = SectionPos.asLong(sx, sy, sz);
                        watches[at] =
                                VERSIONS.compute(
                                        sections[at],
                                        (key, old) -> {
                                            var value = old == null ? new Version() : old;
                                            value.users++;
                                            return value;
                                        });
                        versions[at] = watches[at].value.get();
                        at++;
                    }
            var cursor = new BlockPos.MutableBlockPos();
            for (int iz = 0; iz < nz; iz++)
                for (int iy = 0; iy < ny; iy++)
                    for (int ix = 0; ix < nx; ix++) {
                        cursor.set(x + ix, y + iy, z + iz);
                        int cell = (ix + nx * (iy + ny * iz)) * 2;
                        ranges[cell] = boxes.size();
                        for (var box :
                                level.getBlockState(cursor).getShape(level, cursor).toAabbs())
                            boxes.add(box.move(ix, iy, iz));
                        ranges[cell + 1] = boxes.size() - ranges[cell];
                    }
        }

        boolean valid(AABB requested) {
            if (requested.minX < bounds.minX
                    || requested.minY < bounds.minY
                    || requested.minZ < bounds.minZ
                    || requested.maxX > bounds.maxX
                    || requested.maxY > bounds.maxY
                    || requested.maxZ > bounds.maxZ) return false;
            for (int i = 0; i < sections.length; i++)
                if (versions[i] != watches[i].value.get()) return false;
            return true;
        }

        void close() {
            for (int i = 0; i < sections.length; i++) {
                var watch = watches[i];
                VERSIONS.computeIfPresent(
                        sections[i],
                        (key, current) ->
                                current == watch && --current.users == 0 ? null : current);
            }
        }
    }

    public ComputedSurface update(ClientLevel level, AABB plotBounds, AABB worldBounds) {
        boolean changed = false;
        if (plot == null || !plot.valid(plotBounds)) {
            var replacement = new Grid(level, plotBounds);
            if (plot != null) plot.close();
            plot = replacement;
            changed = true;
        }
        if (world == null || !world.valid(worldBounds)) {
            var replacement = new Grid(level, worldBounds);
            if (world != null) world.close();
            world = replacement;
            changed = true;
        }
        if (!changed) return data;
        int words = 16;
        for (var grid : new Grid[] {plot, world})
            words = Math.addExact(words, grid.ranges.length + grid.boxes.size() * 8);
        ByteBuffer buffer =
                MemoryUtil.memCalloc(Math.multiplyExact(words, 4)).order(ByteOrder.nativeOrder());
        try {
            int offset = 16;
            for (int g = 0; g < 2; g++) {
                var grid = g == 0 ? plot : world;
                int head = g * 32, boxOffset = offset + grid.ranges.length;
                buffer.putInt(head, offset)
                        .putInt(head + 4, grid.nx)
                        .putInt(head + 8, grid.ny)
                        .putInt(head + 12, grid.nz)
                        .putInt(head + 16, boxOffset);
                for (int value : grid.ranges) {
                    buffer.putInt(offset * 4, value);
                    offset++;
                }
                for (var box : grid.boxes) {
                    buffer.putFloat(offset * 4, (float) box.minX)
                            .putFloat((offset + 1) * 4, (float) box.minY)
                            .putFloat((offset + 2) * 4, (float) box.minZ)
                            .putFloat((offset + 4) * 4, (float) box.maxX)
                            .putFloat((offset + 5) * 4, (float) box.maxY)
                            .putFloat((offset + 6) * 4, (float) box.maxZ);
                    offset += 8;
                }
            }
            var replacement = ComputedSurface.data(buffer);
            if (data != null) data.close();
            data = replacement;
            builds++;
            return data;
        } finally {
            MemoryUtil.memFree(buffer);
        }
    }

    public void origins(ByteBuffer parameters, Vector3d pivot, Vector3d centre) {
        parameters
                .putFloat(224, (float) (plot.x - pivot.x))
                .putFloat(228, (float) (plot.y - pivot.y))
                .putFloat(232, (float) (plot.z - pivot.z));
        parameters
                .putFloat(240, (float) (world.x - centre.x))
                .putFloat(244, (float) (world.y - centre.y))
                .putFloat(248, (float) (world.z - centre.z));
    }

    public void close() {
        if (data != null) data.close();
        if (plot != null) plot.close();
        if (world != null) world.close();
        data = null;
        plot = world = null;
    }
}
