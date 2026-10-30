package com.radiance.extra.compatibility.flywheel;

import com.radiance.api.render.InstanceParameters;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** Interprets the validated upstream layout once when the source marks an instance changed. */
public final class FlywheelInstanceDecoder {
    private FlywheelInstanceDecoder() {}

    public static InstanceParameters decode(
            FlywheelInstanceAdapter adapter, ByteBuffer source, ByteBuffer target) {
        ByteBuffer d = source.duplicate().order(ByteOrder.nativeOrder());
        InstanceParameters p = new InstanceParameters(target);
        Matrix4f pose = new Matrix4f();
        Matrix3f normal = null;
        int color = -1, light = -1, overlay = -1;
        boolean multiply = true;
        switch (adapter) {
            case TRANSFORMED, POSED -> {
                color = 0;
                light = 8;
                overlay = 4;
                pose = matrix(d, 12);
                if (adapter == FlywheelInstanceAdapter.POSED) {
                    float[] n = new float[9];
                    for (int i = 0; i < 9; i++) n[i] = d.getFloat(76 + i * 4);
                    normal = new Matrix3f().set(n);
                }
            }
            case ORIENTED -> {
                color = 0;
                light = 8;
                overlay = 4;
                Vector3f pos = vec(d, 12), pivot = vec(d, 24);
                pose.translation(new Vector3f(pos).add(pivot))
                        .rotate(quat(d, 36))
                        .translate(new Vector3f(pivot).negate());
            }
            case SHADOW -> {
                Vector3f pos = vec(d, 0);
                float width = d.getFloat(20), depth = d.getFloat(24);
                pose.translation(pos).scale(width, 1, depth);
                normal = new Matrix3f();
                p.shadow(
                        width,
                        depth,
                        pos.x - d.getFloat(12),
                        pos.z - d.getFloat(16),
                        d.getFloat(32),
                        d.getFloat(28));
            }
            case ROTATING -> {
                color = 0;
                light = 4;
                overlay = 8;
                Vector3f pivot = vec(d, 28).add(.5f, .5f, .5f);
                pose.translation(pivot).rotate(quat(d, 12)).translate(-.5f, -.5f, -.5f);
                Vector3f axis = new Vector3f(d.get(48) / 127f, d.get(49) / 127f, d.get(50) / 127f);
                if (axis.lengthSquared() > 0 || d.getFloat(40) != 0 || d.getFloat(44) != 0)
                    p.rotate(axis, pivot, d.getFloat(40), d.getFloat(44));
            }
            case SCROLLING -> {
                color = 0;
                light = 4;
                overlay = 8;
                multiply = false;
                pose.translation(vec(d, 12).add(.5f, .5f, .5f))
                        .rotate(quat(d, 24))
                        .translate(-.5f, -.5f, -.5f);
                scroll(p, d, 40);
            }
            case SCROLLING_TRANSFORMED -> {
                color = 64;
                light = 68;
                overlay = 72;
                multiply = false;
                pose = matrix(d, 0);
                scroll(p, d, 76);
            }
            case FLUID -> {
                color = 64;
                light = 68;
                overlay = 72;
                multiply = false;
                pose = matrix(d, 0);
                normal = new Matrix3f(pose).invert().transpose();
                float progress = d.getFloat(76);
                pose.scale(1, progress, 1);
                p.replaceColor(
                        channel(d, color),
                        channel(d, color + 1),
                        channel(d, color + 2),
                        channel(d, color + 3));
                p.fluid(progress, d.getFloat(80), d.getFloat(84));
            }
        }
        if (color >= 0) {
            if (multiply)
                p.color(
                        channel(d, color),
                        channel(d, color + 1),
                        channel(d, color + 2),
                        channel(d, color + 3));
            // This is world PT input. Preserve the overlay, not the source lightmap coordinate.
            p.lighting(0, d.getInt(overlay));
        }
        if (normal == null) normal = new Matrix3f(pose).invert().transpose();
        return p.pose(pose, normal);
    }

    private static void scroll(InstanceParameters p, ByteBuffer d, int at) {
        p.scroll(
                d.getFloat(at),
                d.getFloat(at + 4),
                d.getFloat(at + 16),
                d.getFloat(at + 20),
                d.getFloat(at + 24),
                d.getFloat(at + 28),
                d.getFloat(at + 8),
                d.getFloat(at + 12));
    }

    private static float channel(ByteBuffer d, int at) {
        return Byte.toUnsignedInt(d.get(at)) / 255f;
    }

    private static Vector3f vec(ByteBuffer d, int at) {
        return new Vector3f(d.getFloat(at), d.getFloat(at + 4), d.getFloat(at + 8));
    }

    private static Quaternionf quat(ByteBuffer d, int at) {
        return new Quaternionf(
                d.getFloat(at), d.getFloat(at + 4), d.getFloat(at + 8), d.getFloat(at + 12));
    }

    private static Matrix4f matrix(ByteBuffer d, int at) {
        float[] m = new float[16];
        for (int i = 0; i < 16; i++) m[i] = d.getFloat(at + i * 4);
        return new Matrix4f().set(m);
    }
}
