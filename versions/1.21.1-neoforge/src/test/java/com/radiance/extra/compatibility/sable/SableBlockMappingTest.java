package com.radiance.extra.compatibility.sable;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import org.joml.Matrix4d;
import org.junit.jupiter.api.Test;

class SableBlockMappingTest {
    @Test
    void rotationsAndNegativePlotCoordinatesUseOneIntegerTransform() {
        var anchor = new BlockPos(-30000001, 73, 24000002);
        var goal = new BlockPos(17, 81, -29);
        for (int turns = 0; turns < 4; turns++) {
            var matrix =
                    new Matrix4d()
                            .translation(goal.getX() + .5, goal.getY() + .5, goal.getZ() + .5)
                            .rotateY(turns * Math.PI / 2)
                            .translate(
                                    -anchor.getX() - .5, -anchor.getY() - .5, -anchor.getZ() - .5);
            var mapping = SableBlockMapping.aligned(matrix, anchor);
            assertNotNull(mapping);
            assertEquals(goal, mapping.apply(anchor));
            var point = anchor.offset(143, 7, -231);
            var exact =
                    matrix.transformPosition(
                            new org.joml.Vector3d(
                                    point.getX() + .5, point.getY() + .5, point.getZ() + .5));
            assertEquals(BlockPos.containing(exact.x, exact.y, exact.z), mapping.apply(point));
        }
    }

    @Test
    void smallAlignmentErrorDoesNotAccumulateOverLongStructure() {
        var mapping = SableBlockMapping.aligned(new Matrix4d().rotateY(.01), BlockPos.ZERO);
        assertNotNull(mapping);
        assertEquals(new BlockPos(10000, 7, -10000), mapping.apply(new BlockPos(10000, 7, -10000)));
        assertNull(SableBlockMapping.aligned(new Matrix4d().rotateY(Math.PI / 4), BlockPos.ZERO));
        assertNull(SableBlockMapping.aligned(new Matrix4d().scale(2), BlockPos.ZERO));
    }

    @Test
    void explicitMergeAnchorOverridesSimulationAlignmentResidual() {
        var from = new BlockPos(700, 80, -1200);
        var to = new BlockPos(-901, 90, 500);
        var mapping = SableBlockMapping.anchored(0, 1, -1, 0, from, to);
        assertEquals(to, mapping.apply(from));
        assertEquals(to.offset(-3, 2, -5), mapping.apply(from.offset(5, 2, -3)));
    }
}
