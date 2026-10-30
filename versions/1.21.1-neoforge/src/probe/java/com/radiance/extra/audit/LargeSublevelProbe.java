package com.radiance.extra.audit;

import com.radiance.audit.BenchmarkChunkState;
import com.radiance.audit.FrameProfiler;
import com.radiance.client.proxy.world.ChunkProxy;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.player.Input;

/** Bounded observation of the copied user world; never constructs a substitute structure. */
final class LargeSublevelProbe {
    private static final org.slf4j.Logger LOG = com.mojang.logging.LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("radiance.audit.largeSublevel");
    private static final boolean ORDERING_ONLY =
            Boolean.getBoolean("radiance.audit.sectionOrderingOnly");
    private static final int ORDERING_EXPECTED_READY =
            Integer.getInteger("radiance.audit.orderingExpectedReady", -1);
    private static boolean orderingReady = ORDERING_EXPECTED_READY < 0;
    private static final Path OUTPUT = Path.of(System.getProperty("validation.result", "."));
    private static final com.google.gson.Gson JSON = new com.google.gson.Gson();
    private static int step;
    private static long deadline, entered, nextSnapshot;
    private static boolean moving;
    private static Input previous;
    private static Field externalChunks, nativeId;
    private static CompletableFuture<Integer> serverCount;
    private static final Input INPUT =
            new Input() {
                @Override
                public void tick(boolean slow, float factor) {
                    forwardImpulse = moving ? 0.7F : 0;
                    leftImpulse = 0;
                    up = moving;
                    down = left = right = jumping = shiftKeyDown = false;
                }
            };

    static void poll(Minecraft mc) {
        if (!ENABLED || step == 8) return;
        long now = System.nanoTime();
        try {
            if (deadline == 0) deadline = now + 360_000_000_000L;
            if (now > deadline)
                throw new IllegalStateException("Large-sublevel observation timed out");
            if (step == 5) {
                if (SceneLifecycleProbe.poll(mc, now)) finish(mc);
                return;
            }
            if (step == 0 && mc.screen instanceof TitleScreen && mc.getOverlay() == null) {
                if (!com.radiance.audit.CityFrameComparisonProbe.viewportReady(mc, now)) return;
                step = 1;
                mc.createWorldOpenFlows()
                        .openWorld("Newport City", () -> mc.setScreen(new TitleScreen()));
            }
            if (mc.level == null || mc.player == null) return;
            if (step == 1) {
                previous = mc.player.input;
                mc.player.input = INPUT;
                mc.setScreen(new ChatScreen(""));
                entered = now;
                if (Boolean.getBoolean("radiance.audit.sublevelCityView")) {
                    mc.player.setYRot(mc.player.getYRot() + 180);
                    mc.player.yRotO = mc.player.getYRot();
                    LOG.info(
                            "LARGE_SUBLEVEL city-view: turn 180 degrees from the saved pose; geometry and quality unchanged");
                }
                step = 2;
                LOG.info(
                        "LARGE_SUBLEVEL world-ready position={} rotation={}/{} viewport={}x{}",
                        mc.player.position(),
                        mc.player.getYRot(),
                        mc.player.getXRot(),
                        mc.getWindow().getWidth(),
                        mc.getWindow().getHeight());
                if (!ORDERING_ONLY) request("loading", 20);
            }
            if (now >= nextSnapshot && (step <= 3 || !FrameProfiler.requested())) {
                nextSnapshot = now + 1_000_000_000L;
                snapshot(mc, now);
            }
            if (ORDERING_ONLY) {
                if (now - entered >= 45_000_000_000L && orderingReady) {
                    Screenshot.grab(
                            mc.gameDirectory,
                            "section-ordering.png",
                            mc.getMainRenderTarget(),
                            m -> {});
                    finish(mc);
                }
                return;
            }
            if (step == 2
                    && Boolean.getBoolean("radiance.audit.sourceSceneLifecycle")
                    && now - entered >= 25_000_000_000L
                    && !FrameProfiler.requested()) {
                // This case checks lifecycle only; timed A/B windows use separate clean copies.
                step = 5;
                return;
            }
            if (step == 2 && now - entered >= 65_000_000_000L && !FrameProfiler.requested()) {
                Screenshot.grab(
                        mc.gameDirectory,
                        "large-sublevel-before.png",
                        mc.getMainRenderTarget(),
                        m -> {});
                request("standing", 12);
                step = 3;
            } else if (step == 3 && !FrameProfiler.requested()) {
                moving = true;
                request("moving", 12);
                step = 4;
            } else if (step == 4 && !FrameProfiler.requested()) {
                moving = false;
                Screenshot.grab(
                        mc.gameDirectory,
                        "large-sublevel-after-move.png",
                        mc.getMainRenderTarget(),
                        m -> {});
                if (Boolean.getBoolean("radiance.audit.sourceSceneLifecycle")) step = 5;
                else finish(mc);
            }
        } catch (Exception | LinkageError failure) {
            step = 8;
            LOG.error("LARGE_SUBLEVEL failed; stop without retry", failure);
            try {
                Files.writeString(OUTPUT.resolve("probe-failure.txt"), failure.toString());
            } catch (java.io.IOException ignored) {
                LOG.error("Could not retain probe failure", ignored);
            }
            mc.stop();
        }
    }

    private static void finish(Minecraft mc) {
        if (mc.player != null) mc.player.input = previous;
        step = 8;
        LOG.info(
                "LARGE_SUBLEVEL observation-complete normal-stop; image/structure acceptance is separate");
        mc.stop();
    }

    private static void request(String phase, int seconds) throws java.io.IOException {
        Files.writeString(
                OUTPUT.resolve("phases.jsonl"),
                JSON.toJson(Map.of("phase", phase, "java_ns", System.nanoTime())) + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.APPEND);
        if (FrameProfiler.request(seconds, "sublevel-" + phase) != 1)
            throw new IllegalStateException("Profiler was already active");
    }

    private static void snapshot(Minecraft mc, long now) throws Exception {
        long started = System.nanoTime();
        var container = SubLevelContainer.getContainer(mc.level);
        var rows = new ArrayList<Map<String, Object>>();
        if (externalChunks == null) {
            externalChunks = ChunkProxy.class.getDeclaredField("externalChunks");
            externalChunks.setAccessible(true);
        }
        Map<?, ?> tracked = (Map<?, ?>) externalChunks.get(null);
        if (container != null)
            for (var sublevel : container.getAllSubLevels()) {
                var row = new LinkedHashMap<String, Object>();
                row.put("uuid", sublevel.getUniqueId().toString());
                var space =
                        com.radiance.extra.compatibility.sable.SableSceneRegistry.sharedSpace(
                                sublevel);
                row.put("renderSpace", space == null ? 0 : space.space().handle());
                row.put("bounds", sublevel.boundingBox().toString());
                row.put(
                        "pose",
                        sublevel.renderPose(mc.getTimer().getGameTimeDeltaPartialTick(false))
                                .toString());
                var data = sublevel.getRenderData();
                row.put("renderer", data == null ? "null" : data.getClass().getName());
                if (data instanceof VanillaChunkedSubLevelRenderData chunks) {
                    int dirty = 0, registered = 0, ready = 0;
                    var handles = new ArrayList<Long>();
                    for (var section : chunks.allRenderSections()) {
                        if (section.isDirty()) dirty++;
                        Object owner = tracked.get(section);
                        if (owner != null) {
                            if (nativeId == null) {
                                nativeId = owner.getClass().getDeclaredField("nativeId");
                                nativeId.setAccessible(true);
                            }
                            long id = nativeId.getLong(owner);
                            registered++;
                            handles.add(id);
                            if (ChunkProxy.isChunkReady(id)) ready++;
                        }
                    }
                    row.put("sections", chunks.allRenderSections().size());
                    row.put("dirty", dirty);
                    row.put("registered", registered);
                    row.put("ready", ready);
                    row.put("nativeHandles", handles);
                }
                rows.add(row);
            }
        if (ORDERING_ONLY && ORDERING_EXPECTED_READY >= 0) {
            var expectedSource = System.getProperty("radiance.audit.orderingExpectedSource", "");
            orderingReady =
                    rows.stream()
                            .anyMatch(
                                    row ->
                                            (expectedSource.isEmpty()
                                                            || expectedSource.equals(
                                                                    row.get("uuid")))
                                                    && row.get("ready") instanceof Number ready
                                                    && ready.intValue() == ORDERING_EXPECTED_READY
                                                    && row.get("dirty") instanceof Number dirty
                                                    && dirty.intValue() == 0
                                                    && java.util.Objects.equals(
                                                            row.get("registered"),
                                                            row.get("sections")));
        }
        if (serverCount == null || serverCount.isDone()) {
            int last = serverCount == null ? -1 : serverCount.join();
            var server = mc.getSingleplayerServer();
            var dimension = mc.level.dimension();
            serverCount =
                    CompletableFuture.supplyAsync(
                            () -> {
                                var c = SubLevelContainer.getContainer(server.getLevel(dimension));
                                return c == null ? 0 : c.getAllSubLevels().size();
                            },
                            server);
            var state = new Properties();
            BenchmarkChunkState.capture(state);
            Files.writeString(
                    OUTPUT.resolve("sublevels.jsonl"),
                    JSON.toJson(
                                    Map.of(
                                            "java_ns",
                                            now,
                                            "step",
                                            step,
                                            "serverCountPrevious",
                                            last,
                                            "player",
                                            mc.player.position().toString(),
                                            "clientSublevels",
                                            rows,
                                            "handoffs",
                                            com.radiance.extra.compatibility.sable
                                                    .SableSceneRegistry.handoffCounters(),
                                            "chunkState",
                                            state,
                                            "snapshotNs",
                                            System.nanoTime() - started))
                            + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        }
        String trace = ChunkProxy.drainUpdateTraceNative();
        if (!trace.isBlank())
            Files.writeString(
                    OUTPUT.resolve("chunk-trace.txt"),
                    trace + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
    }
}
