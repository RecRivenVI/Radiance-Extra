package com.radiance.extra.compatibility.catnip;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.radiance.api.render.LocalGeometry;
import com.radiance.api.render.LocalSurface;
import com.radiance.render.contract.DrawStateSnapshot.UvTransform;
import com.radiance.render.contract.RenderAbi;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.TemplateMesh;

/** Catnip owns the immutable template; Radiance retains geometry and varies only its attributes. */
public final class CatnipTemplateGeometry {
    private static final boolean SOURCE_AUDIT = Boolean.getBoolean("radiance.extra.sourceAudit");
    private static final java.util.concurrent.atomic.LongAdder REQUESTS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder ACCEPTED =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder DEFINITIONS =
            new java.util.concurrent.atomic.LongAdder();
    private static final java.util.concurrent.atomic.LongAdder VERTICES =
            new java.util.concurrent.atomic.LongAdder();

    public static long[] workCounters() {
        return new long[] {REQUESTS.sum(), ACCEPTED.sum(), DEFINITIONS.sum(), VERTICES.sum()};
    }

    private CatnipTemplateGeometry() {}

    public static UvTransform shift(SpriteShiftEntry entry, float u, float v, float scale) {
        var from = entry.getOriginal();
        var to = entry.getTarget();
        float su = (to.getU1() - to.getU0()) / (from.getU1() - from.getU0()) * scale;
        float sv = (to.getV1() - to.getV0()) / (from.getV1() - from.getV0()) * scale;
        return new UvTransform(
                su,
                0,
                to.getU0() + u - from.getU0() * su,
                0,
                sv,
                to.getV0() + v - from.getV0() * sv);
    }

    public static boolean submit(
            TemplateMesh template,
            PoseStack.Pose pose,
            VertexConsumer out,
            float r,
            float g,
            float b,
            float a,
            boolean overlay,
            int packedOverlay,
            UvTransform uv) {
        // Mutable/custom mesh implementations cannot borrow the immutable-template contract.
        if (template.getClass() != TemplateMesh.class || template.vertexCount() % 4 != 0)
            return false;
        int flags = RenderAbi.APPEARANCE_COLOR_MULTIPLY | RenderAbi.APPEARANCE_UV_AFFINE;
        if (overlay) flags |= RenderAbi.APPEARANCE_OVERRIDE_OVERLAY;
        if (SOURCE_AUDIT) REQUESTS.increment();
        boolean accepted =
                LocalGeometry.submit(
                        template,
                        pose,
                        out,
                        new LocalSurface(flags, 0, packedOverlay, uv, r, g, b, a),
                        (local, consumer) -> emit(template, local, consumer));
        if (SOURCE_AUDIT && accepted) ACCEPTED.increment();
        return accepted;
    }

    static void emit(TemplateMesh mesh, PoseStack.Pose pose, VertexConsumer out) {
        if (SOURCE_AUDIT) {
            DEFINITIONS.increment();
            VERTICES.add(mesh.vertexCount());
        }
        for (int i = 0; i < mesh.vertexCount(); i++) {
            int color = mesh.color(i), normal = mesh.normal(i);
            out.addVertex(pose, mesh.x(i), mesh.y(i), mesh.z(i))
                    .setColor(
                            color & 255, color >>> 8 & 255, color >>> 16 & 255, color >>> 24 & 255)
                    .setUv(mesh.u(i), mesh.v(i))
                    .setOverlay(mesh.overlay(i))
                    .setLight(0)
                    .setNormal(
                            pose,
                            (byte) normal / 127f,
                            (byte) (normal >>> 8) / 127f,
                            (byte) (normal >>> 16) / 127f);
        }
    }
}
