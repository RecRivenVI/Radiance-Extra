package com.radiance.extra.compatibility.outliner;

import com.radiance.extra.compatibility.catnip.CatnipWorldGeometryCapture;
import net.createmod.catnip.outliner.Outline;

/** Captures original Outliner geometry with ordinary PBR albedo emission. */
public final class RadianceOutlinerContext extends CatnipWorldGeometryCapture {
    public RadianceOutlinerContext(Outline outline, Object slot) {
        super(outline, "radiance/outliner/" + slot, 1.0F);
    }
}
