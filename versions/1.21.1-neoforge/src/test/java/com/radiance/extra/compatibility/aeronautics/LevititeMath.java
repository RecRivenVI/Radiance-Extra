package com.radiance.extra.compatibility.aeronautics;

import org.joml.Matrix3f;
import org.joml.Vector3dc;
import org.joml.Vector3f;
import org.joml.Vector3fc;

/** Aeronautics 1.3.2 tessellation equations, evaluated in source-local coordinates. */
public final class LevititeMath {
    @FunctionalInterface
    public interface Noise {
        Vector3f sample(float u, float v);
    }

    public record Motion(
            Vector3f linear,
            Vector3f angular,
            Vector3f gravity,
            float transition,
            Matrix3f slow,
            Matrix3f fast,
            boolean moving) {
        public Vector3f velocity(Vector3fc point) {
            return new Vector3f(linear).sub(new Vector3f(point).cross(angular));
        }

        public float fullness(Vector3fc point) {
            float speed = velocity(point).length();
            return (1 - (float) Math.exp(-2 * speed / transition))
                    * (1 - (float) Math.exp(-gravity.length() / 3));
        }

        public Vector3f drag(Vector3fc velocity) {
            float x2 = velocity.lengthSquared() / (transition * transition);
            float q = (float) Math.exp(-1.5 * x2);
            float h0 = new Vector3f(velocity).dot(fast.transform(velocity, new Vector3f()));
            float h1 = new Vector3f(velocity).dot(slow.transform(velocity, new Vector3f()));
            float peak = -.446260320297f * (h1 - h0) + h0;
            float d = peak > 0 ? ((1 - 3 * x2) * q * (h1 - h0) + h0) / peak : 0;
            return new Vector3f(q, 1 - q, Math.max(d, 0));
        }

        public Vector3f control(Vector3fc point, int layer) {
            var velocity = velocity(point);
            float length = velocity.length();
            return velocity.mul(
                            (1 - (float) Math.exp(-length / transition))
                                    / (length + .001f)
                                    * layer
                                    * .15f)
                    .add(point);
        }
    }

    private LevititeMath() {}

    /** Cancel the large plot origin in double before introducing float-local vertices. */
    public static Vector3f localPosition(
            Vector3fc position, double x, double y, double z, Vector3dc rotationPoint) {
        return new Vector3f(
                (float) (x - rotationPoint.x() + position.x()),
                (float) (y - rotationPoint.y() + position.y()),
                (float) (z - rotationPoint.z() + position.z()));
    }

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

    public static Vector3f noise(Noise image, Vector3fc p, float time, Vector3fc scale) {
        Vector3f a =
                new Vector3f(
                        p.x() + .4f * p.y() - .3f * p.z(),
                        .2f * p.x() + p.y() - .2f * p.z(),
                        -.1f * p.x() - .5f * p.y() + p.z());
        Vector3f b =
                new Vector3f(
                        p.x() + .2f * p.y() - .1f * p.z(),
                        .4f * p.x() + p.y() - .5f * p.z(),
                        -.3f * p.x() - .2f * p.y() + p.z());
        return new Vector3f(
                pair(image, a.z, b.y, time).dot(scale),
                pair(image, a.x, b.z, time).dot(scale),
                pair(image, a.y, b.x, time).dot(scale));
    }

    private static Vector3f pair(Noise image, float a, float b, float time) {
        return image.sample(2.5f * a + .2f * time, .3f * a + 1.5f * time)
                .sub(image.sample(1.5f * b + .2f * time, .1f * b - 2.5f * time));
    }

    public static Vector3f deform(Motion motion, Noise image, Vector3fc point, float time) {
        var velocity = motion.velocity(point);
        float speed = velocity.length(), falloff = (float) Math.exp(-speed / motion.transition);
        var drag = motion.drag(velocity);
        var nudge =
                noise(
                        image,
                        new Vector3f(point).div(50),
                        time / 200,
                        new Vector3f(drag.y * .4f, drag.x * .3f, drag.z * .2f));
        if (speed > .0001f) {
            var parallel =
                    new Vector3f(velocity).mul(nudge.dot(velocity) / velocity.lengthSquared());
            nudge = parallel.lerp(nudge, 1 - .9f * (1 - falloff) * (1 - falloff));
        }
        return nudge.mul(motion.fullness(point)).add(point);
    }

    public static float shimmer(
            Motion motion,
            Noise image,
            Vector3fc local,
            Vector3fc global,
            float time,
            boolean sublevel) {
        var localNoise =
                noise(image, new Vector3f(local).div(10), time / 500, new Vector3f(.7f, 0, 0));
        var drag = sublevel ? motion.drag(motion.velocity(local)) : new Vector3f(1, 0, 0);
        var n =
                noise(
                        image,
                        new Vector3f(global).div(10),
                        time / 500,
                        new Vector3f(drag.y * .7f, drag.x, drag.z));
        n = localNoise.lerp(n, 1 - (float) Math.exp(-motion.gravity.length() / 3));
        return Math.abs(n.x + n.y + n.z) * Math.max(.3f, motion.fullness(local));
    }

    public static Vector3f bilinear(Vector3fc[] points, float u, float v) {
        return new Vector3f(points[0])
                .lerp(points[3], v)
                .lerp(new Vector3f(points[1]).lerp(points[2], v), u);
    }
}
