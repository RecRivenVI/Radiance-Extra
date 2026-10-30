package com.radiance.extra.compatibility.aeronautics;

import org.joml.Matrix3f;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Source parameters only; per-point noise, colour and deformation are evaluated on the GPU. */
public record LevititeMotion(
        Vector3f linear,
        Vector3f angular,
        Vector3f gravity,
        float transition,
        Matrix3f slow,
        Matrix3f fast,
        boolean moving) {
    public static Matrix3f dragMatrix(Vector3fc g, float vertical, float horizontal) {
        float squared = g.lengthSquared();
        Matrix3f matrix = new Matrix3f();
        if (squared > 1e-5f) {
            float k = (horizontal - vertical) / squared;
            matrix.set(
                    g.x() * g.x() * k,
                    g.y() * g.x() * k,
                    g.z() * g.x() * k,
                    g.x() * g.y() * k,
                    g.y() * g.y() * k,
                    g.z() * g.y() * k,
                    g.x() * g.z() * k,
                    g.y() * g.z() * k,
                    g.z() * g.z() * k);
        }
        return matrix.m00(matrix.m00() - horizontal)
                .m11(matrix.m11() - horizontal)
                .m22(matrix.m22() - horizontal);
    }
}
