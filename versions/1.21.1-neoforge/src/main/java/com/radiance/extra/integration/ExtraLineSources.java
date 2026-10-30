package com.radiance.extra.integration;

import com.radiance.client.render.LineSources;
import com.radiance.render.contract.LineContract.SourceKey;
import com.radiance.render.contract.LineContract.WidthPolicy;

public final class ExtraLineSources {
    public static final SourceKey SABLE = key(3, "sable/sublevel_capture", false);
    public static final SourceKey SIMULATED_LEVER = key(4, "simulated/lever_outline", false);
    public static final SourceKey SIMULATED_ROPE = key(5, "simulated/rope_outline", false);
    public static final SourceKey VEIL_EVENT = key(7, "veil/world_event", true);
    public static final SourceKey CREATE_HIGHLIGHT = key(8, "create/highlight_capture", true);
    public static final SourceKey CREATE_EVENT = key(9, "create/world_event", true);

    private static SourceKey key(int id, String name, boolean debug) {
        return LineSources.register(
                new SourceKey(id, name), debug ? WidthPolicy.DEBUG : WidthPolicy.ORDINARY);
    }

    private ExtraLineSources() {}
}
