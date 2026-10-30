package com.radiance.extra.compatibility.sable;

import net.minecraft.core.BlockPos;
import org.joml.Matrix4dc;
import org.joml.Vector3d;

/** One coherent block-lattice transform, rather than rounding each distant block independently. */
record SableBlockMapping(int xx, int xz, int zx, int zz, int x, int y, int z) {
    static SableBlockMapping aligned(Matrix4dc matrix, BlockPos anchor) {
        var a = matrix.transformDirection(new Vector3d(1, 0, 0));
        var b = matrix.transformDirection(new Vector3d(0, 1, 0));
        var c = matrix.transformDirection(new Vector3d(0, 0, 1));
        int xx = (int) Math.round(a.x), zx = (int) Math.round(a.z);
        int xz = (int) Math.round(c.x), zz = (int) Math.round(c.z);
        if (Math.abs(xx * zz - xz * zx) != 1
                || a.distance(xx, 0, zx) > .25
                || b.distance(0, 1, 0) > .25
                || c.distance(xz, 0, zz) > .25) return null;
        var point =
                matrix.transformPosition(
                        new Vector3d(anchor.getX() + .5, anchor.getY() + .5, anchor.getZ() + .5));
        if (!point.isFinite()) return null;
        return anchored(xx, xz, zx, zz, anchor, BlockPos.containing(point.x, point.y, point.z));
    }

    static SableBlockMapping anchored(int xx, int xz, int zx, int zz, BlockPos from, BlockPos to) {
        return new SableBlockMapping(
                xx,
                xz,
                zx,
                zz,
                to.getX() - xx * from.getX() - xz * from.getZ(),
                to.getY() - from.getY(),
                to.getZ() - zx * from.getX() - zz * from.getZ());
    }

    BlockPos apply(BlockPos point) {
        return new BlockPos(
                xx * point.getX() + xz * point.getZ() + x,
                point.getY() + y,
                zx * point.getX() + zz * point.getZ() + z);
    }
}
