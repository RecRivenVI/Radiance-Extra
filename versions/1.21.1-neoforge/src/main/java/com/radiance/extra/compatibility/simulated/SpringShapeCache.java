package com.radiance.extra.compatibility.simulated;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.api.render.LocalGeometry;
import com.radiance.api.render.LocalSurface;
import com.radiance.render.contract.DrawStateSnapshot.UvTransform;
import com.radiance.render.contract.RenderAbi;
import java.util.Arrays;
import java.util.WeakHashMap;
import org.joml.Matrix4f;

/** Retains only each connection's current source-local mesh, before any world transform. */
public final class SpringShapeCache {
    private SpringShapeCache() {}

    public record Inputs(
            Object firstState,
            Object secondState,
            double x,
            double y,
            double z,
            double nx,
            double ny,
            double nz,
            double qx,
            double qy,
            double qz,
            double qw) {}

    private static final boolean VERIFY =
            Boolean.getBoolean("radiance.extra.verifySpringTemplates");
    private static long requests, reused, generated, vertices, verified;

    public static long[] counters() {
        return new long[] {requests, reused, generated, vertices, verified};
    }

    public static VertexConsumer consumer(VertexConsumer out) {
        return com.radiance.client.render.RasterPreviewScope.active()
                ? out
                : com.radiance.api.render.SourceHooks.bulkConsumer(out, true);
    }

    public static final class Store {
        private final WeakHashMap<Object, Entry> connections = new WeakHashMap<>();

        public Frame begin(
                Object source,
                Inputs inputs,
                PoseStack.Pose pose,
                VertexConsumer out,
                int light,
                int color,
                float stretch) {
            out = consumer(out);
            if (!Float.isFinite(stretch) || stretch == 0 || !LocalGeometry.supported(pose, out))
                return null;
            requests++;
            Entry entry = connections.computeIfAbsent(source, ignored -> new Entry());
            var surface =
                    new LocalSurface(
                            RenderAbi.APPEARANCE_COLOR_MULTIPLY
                                    | RenderAbi.APPEARANCE_UV_AFFINE
                                    | RenderAbi.APPEARANCE_OVERRIDE_LIGHT,
                            light,
                            0,
                            new UvTransform(1, 0, 0, 0, stretch, 0),
                            ((color >>> 16) & 255) / 255F,
                            ((color >>> 8) & 255) / 255F,
                            (color & 255) / 255F,
                            ((color >>> 24) & 255) / 255F);
            if (VERIFY
                    && requests % 60 == 0
                    && inputs.equals(entry.inputs)
                    && entry.token != null) {
                var comparison = new Frame(new Entry(), inputs, pose, out, surface, stretch);
                comparison.reference = entry;
                return comparison;
            }
            if (inputs.equals(entry.inputs)
                    && entry.token != null
                    && LocalGeometry.submit(entry.token, pose, out, surface, entry::emit)) {
                reused++;
                return Frame.REUSED;
            }
            // Release the lookup before replacing its CPU staging. Earlier views keep their
            // own immutable expanded model; no old curve is retained as another source entry.
            if (entry.token != null) LocalGeometry.release(entry.token);
            entry.inputs = null;
            entry.token = new Object();
            entry.size = 0;
            return new Frame(entry, inputs, pose, out, surface, stretch);
        }
    }

    private static final class Entry {
        Inputs inputs;
        Object token;
        float[] values = new float[1024];
        int size;

        void emit(PoseStack.Pose pose, VertexConsumer out) {
            for (int i = 0; i < size; i += 8)
                out.addVertex(pose.pose(), values[i], values[i + 1], values[i + 2])
                        .setColor(255, 255, 255, 255)
                        .setUv(values[i + 3], values[i + 4])
                        .setUv2(0, 0)
                        .setNormal(pose, values[i + 5], values[i + 6], values[i + 7]);
        }
    }

    /** Records pre-transform float positions, source normals and normalized primary UV. */
    public static final class Frame implements VertexConsumer {
        private static final Frame REUSED = new Frame();
        private Entry entry;
        private Inputs inputs;
        private PoseStack.Pose pose;
        private VertexConsumer out;
        private LocalSurface surface;
        private float stretch;
        private int at;
        private Entry reference;

        private Frame() {}

        private Frame(
                Entry entry,
                Inputs inputs,
                PoseStack.Pose pose,
                VertexConsumer out,
                LocalSurface surface,
                float stretch) {
            this.entry = entry;
            this.inputs = inputs;
            this.pose = pose.copy();
            this.out = out;
            this.surface = surface;
            this.stretch = stretch;
        }

        public boolean reused() {
            return this == REUSED;
        }

        public void finish() {
            if (reused()) return;
            if (reference != null) {
                if (entry.size != reference.size)
                    throw new IllegalStateException(
                            "Spring template extent changed without an input change");
                for (int i = 0; i < entry.size; i++) {
                    float actual = entry.values[i], expected = reference.values[i];
                    float tolerance = i % 8 == 4 ? 2e-6F * Math.max(1, Math.abs(expected)) : 0;
                    if (!Float.isFinite(actual) || Math.abs(actual - expected) > tolerance)
                        throw new IllegalStateException(
                                "Spring local template differs from original producer at " + i);
                }
                verified++;
                entry = reference;
                reference = null;
                reused++;
            }
            if ((entry.size / 8) % 4 != 0)
                throw new IllegalStateException("Incomplete source spring quad");
            if (LocalGeometry.submit(entry.token, pose, out, surface, entry::emit)) {
                entry.inputs = inputs;
                generated++;
                vertices += entry.size / 8;
            } else {
                // Unsupported destinations continue to receive the exact captured source fields.
                for (int i = 0; i < entry.size; i += 8)
                    out.addVertex(
                                    pose.pose(),
                                    entry.values[i],
                                    entry.values[i + 1],
                                    entry.values[i + 2])
                            .setColor(
                                    Math.round(surface.red() * 255),
                                    Math.round(surface.green() * 255),
                                    Math.round(surface.blue() * 255),
                                    Math.round(surface.alpha() * 255))
                            .setUv(entry.values[i + 3], entry.values[i + 4] * stretch)
                            .setUv2(surface.light() & 65535, surface.light() >>> 16)
                            .setNormal(
                                    pose,
                                    entry.values[i + 5],
                                    entry.values[i + 6],
                                    entry.values[i + 7]);
            }
        }

        public VertexConsumer addVertex(Matrix4f matrix, float x, float y, float z) {
            return addVertex(x, y, z);
        }

        public VertexConsumer addVertex(float x, float y, float z) {
            at = entry.size;
            if (at + 8 > entry.values.length)
                entry.values = Arrays.copyOf(entry.values, entry.values.length * 2);
            entry.size += 8;
            entry.values[at] = x;
            entry.values[at + 1] = y;
            entry.values[at + 2] = z;
            return this;
        }

        public VertexConsumer setColor(int r, int g, int b, int a) {
            return this;
        }

        public VertexConsumer setUv(float u, float v) {
            entry.values[at + 3] = u;
            entry.values[at + 4] = v / stretch;
            return this;
        }

        public VertexConsumer setUv1(int u, int v) {
            return this;
        }

        public VertexConsumer setUv2(int u, int v) {
            return this;
        }

        public VertexConsumer setNormal(PoseStack.Pose pose, float x, float y, float z) {
            return setNormal(x, y, z);
        }

        public VertexConsumer setNormal(float x, float y, float z) {
            entry.values[at + 5] = x;
            entry.values[at + 6] = y;
            entry.values[at + 7] = z;
            return this;
        }
    }
}
