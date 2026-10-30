package com.radiance.extra.integration;

import com.radiance.api.render.SourceIntegration;
import net.minecraft.client.renderer.RenderType;

/** Interprets Catnip/Ponder layer definitions; the core only receives generic overlay semantics. */
public final class OutlineIntegration implements SourceIntegration.Geometry {
    public String id() {
        return "radiance_extra:outlines";
    }

    public Overlay orderedOverlay(RenderType layer) {
        if (layer.name.startsWith("ponder:outline_solid")) return Overlay.OPAQUE;
        if (layer.name.startsWith("ponder:outline_translucent")) return Overlay.SOURCE_OVER;
        return Overlay.NONE;
    }

    public boolean supportsWorldProgram(String program) {
        return program.equals("create:glowing_shader")
                || program.equals("create:rendertype/glowing_shader")
                || program.endsWith(":glowing_shader");
    }
}
