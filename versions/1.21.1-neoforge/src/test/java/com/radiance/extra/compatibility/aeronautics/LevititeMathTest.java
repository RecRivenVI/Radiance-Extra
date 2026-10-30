package com.radiance.extra.compatibility.aeronautics;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Matrix3f;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3fc;
import org.junit.jupiter.api.Test;

class LevititeMathTest {
    private static final LevititeMath.Noise NOISE =
            (u, v) ->
                    new Vector3f(
                            (float) Math.sin(u * 3 + v),
                            (float) Math.cos(u - v * 2),
                            (float) Math.sin(u + v));

    private static LevititeMath.Motion motion(Vector3f linear, Vector3f angular, Vector3f gravity) {
        return new LevititeMath.Motion(
                linear,
                angular,
                gravity,
                3,
                LevititeMath.dragMatrix(gravity, 2, 1.5f),
                LevititeMath.dragMatrix(gravity, .1f, .05f),
                true);
    }

    @Test
    void zeroGravitySuppressesBothGhostsButKeepsBodyShimmer() {
        var m = motion(new Vector3f(4, 2, 0), new Vector3f(0, 1, 0), new Vector3f());
        var p = new Vector3f(1, 2, 3);
        assertEquals(0, m.fullness(p));
        assertTrue(LevititeMath.deform(m, NOISE, p, 90).distance(p) < 1e-6);
        assertTrue(LevititeMath.shimmer(m, NOISE, p, p, 90, true) > 0);
    }

    @Test
    void sourceVelocityIncludesRotationAndOppositeControlOffsets() {
        var m = motion(new Vector3f(3, 0, 0), new Vector3f(0, 1, 0), new Vector3f(0, -9.8f, 0));
        var p = new Vector3f(0, 0, 2);
        assertEquals(new Vector3f(5, 0, 0), m.velocity(p));
        assertTrue(m.control(p, 1).sub(p).add(m.control(p, -1).sub(p)).length() < 1e-6);
    }

    @Test
    void noiseDeformationIsNotACommonRigidTranslation() {
        var m = motion(new Vector3f(7, 1, 0), new Vector3f(.2f, 1, .4f), new Vector3f(0, -9.8f, 0));
        var a = new Vector3f(0, 0, 0);
        var b = new Vector3f(3, 4, 1);
        var da = LevititeMath.deform(m, NOISE, a, 123).sub(a);
        var db = LevititeMath.deform(m, NOISE, b, 123).sub(b);
        assertTrue(da.distance(db) > 1e-4);
        assertTrue(
                LevititeMath.deform(m, NOISE, b, 124)
                                .distance(LevititeMath.deform(m, NOISE, b, 123))
                        > 1e-6);
    }

    @Test
    void sourceDragAnisotropyAndBilinearCornersArePreserved() {
        Matrix3f matrix = LevititeMath.dragMatrix(new Vector3f(0, -1, 0), 2, 1.5f);
        assertEquals(-1.5f, matrix.m00());
        assertEquals(-2, matrix.m11());
        assertEquals(-1.5f, matrix.m22());
        Vector3fc[] q = {
            new Vector3f(), new Vector3f(2, 0, 0), new Vector3f(2, 3, 1), new Vector3f(0, 3, 0)
        };
        assertEquals(q[2], LevititeMath.bilinear(q, 1, 1));
        assertEquals(new Vector3f(1, 1.5f, .25f), LevititeMath.bilinear(q, .5f, .5f));
    }

    @Test
    void plotTranslationPreservesEveryTessellationNodeForOneTwoAndThreeBlocks() {
        var motion =
                motion(
                        new Vector3f(2.4f, .5f, 0),
                        new Vector3f(.2f, .5f, .1f),
                        new Vector3f(0, -9.8f, 0));
        for (int height = 1; height <= 3; height++) {
            for (double origin : new double[] {20481024, -20481024, 1073741824}) {
                var pivot = new Vector3d(origin + 8.5, 128 + height * .5, origin + 8.5);
                for (int y = 0; y <= height * 4; y++) {
                    for (int x = 0; x <= 4; x++) {
                        for (int z = 0; z <= 4; z++) {
                            var vertex = new Vector3f(8 + x * .25f, y * .25f, 8 + z * .25f);
                            var expected =
                                    new Vector3f(
                                            x * .25f - .5f,
                                            y * .25f - height * .5f,
                                            z * .25f - .5f);
                            var actual =
                                    LevititeMath.localPosition(vertex, origin, 128, origin, pivot);
                            assertEquals(
                                    expected, actual, "Large plot origin rounded a local vertex");
                            for (int layer : new int[] {-1, 1}) {
                                assertEquals(
                                        LevititeMath.deform(
                                                motion,
                                                NOISE,
                                                motion.control(expected, layer),
                                                123),
                                        LevititeMath.deform(
                                                motion, NOISE, motion.control(actual, layer), 123));
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void sectionBoundaryAndFractionalPivotKeepDistinctCorners() {
        var pivot = new Vector3d(20481039.875, 130.125, 20485135.75);
        var a = LevititeMath.localPosition(new Vector3f(15, 2, 15), 20481024, 128, 20485120, pivot);
        var b = LevititeMath.localPosition(new Vector3f(0, 2, 0), 20481040, 128, 20485136, pivot);
        assertEquals(new Vector3f(-.875f, -.125f, -.75f), a);
        assertEquals(new Vector3f(.125f, -.125f, .25f), b);
        assertEquals(new Vector3f(1, 0, 1), b.sub(a));
    }
}
