package com.radiance.extra.audit;

import com.radiance.extra.compatibility.aeronautics.LevititeWorldAdapter;
import com.radiance.extra.mixins.compatibility.aeronautics.LevititeMotionAccessor;
import dev.eriksonn.aeronautics.content.blocks.levitite.LevititeShaderManager;
import dev.eriksonn.aeronautics.index.AeroBlocks;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;

/** Same 1024-block sheet, world/static-sublevel/deforming-sublevel, with fixed effect inputs. */
public final class LevititeStressProbe {
    private static int phase = -1;
    private static boolean done;
    private static CompletableFuture<?> pending;
    private static CompletableFuture<?> editTask;
    private static long started, phaseStart, previous, changed, definitions;
    private static long previousCpu, collectBegin, collectNanos, nextEdit;
    private static long sampledNanos, sampleEpoch, phaseDefinitions, quietSince, nextQuietPoll;
    private static boolean initialReady;
    private static int edits;
    private static final java.lang.management.ThreadMXBean CPU =
            java.lang.management.ManagementFactory.getThreadMXBean();
    private static final ArrayList<Double> samples = new ArrayList<>();
    private static final ArrayList<Double> cpuSamples = new ArrayList<>(),
            collectSamples = new ArrayList<>();
    private static final List<Map<String, Object>> results = new ArrayList<>();

    static void poll(Minecraft mc) {
        if (!"levitite-stress".equals(System.getProperty("radiance.audit.worldMaterials134"))
                || done
                || mc.level == null
                || mc.player == null
                || mc.getOverlay() != null) return;
        long now = System.nanoTime();
        try {
            if (started == 0) started = now;
            if (!CPU.isCurrentThreadCpuTimeSupported() || !CPU.isThreadCpuTimeEnabled())
                throw new AssertionError("Render thread CPU clock is unavailable");
            if (now - started > 240_000_000_000L)
                throw new AssertionError("Levitite stress deadline");
            if (pending != null) {
                if (!pending.isDone()) return;
                pending.join();
                pending = null;
                phaseStart = changed = now;
                previous = 0;
            }
            if (phase == -1) {
                pending =
                        mc.getSingleplayerServer()
                                .submit(
                                        () -> {
                                            var server = mc.getSingleplayerServer();
                                            var level = server.overworld();
                                            for (String command :
                                                    List.of(
                                                            "gamerule doMobSpawning false",
                                                            "gamerule doDaylightCycle false",
                                                            "time set noon",
                                                            "weather clear",
                                                            "gamemode spectator @a",
                                                            "tp @a 16 96 58 facing 16 72 16",
                                                            "fill -2 68 -2 34 68 34 minecraft:stone",
                                                            "fill -2 69 -2 34 77 34 minecraft:air"))
                                                server.getCommands()
                                                        .performPrefixedCommand(
                                                                server.createCommandSourceStack()
                                                                        .withPermission(4)
                                                                        .withSuppressedOutput(),
                                                                command);
                                            for (int z = 0; z < 32; z++)
                                                for (int x = 0; x < 32; x++)
                                                    level.setBlock(
                                                            new BlockPos(x, 72, z),
                                                            AeroBlocks.LEVITITE.getDefaultState(),
                                                            3);
                                        });
                phase = 0;
                return;
            }
            var container = SubLevelContainer.getContainer(mc.level);
            if (phase >= 2) {
                if (container == null || container.getAllSubLevels().isEmpty()) return;
            }
            if (phase == 0 && !initialReady) {
                if (now < nextQuietPoll) return;
                nextQuietPoll = now + 1_000_000_000L;
                var state = new java.util.Properties();
                Class.forName("com.radiance.audit.BenchmarkChunkState")
                        .getMethod("capture", java.util.Properties.class)
                        .invoke(null, state);
                if (state.getProperty("chunk.native", "").startsWith("disabled"))
                    throw new AssertionError(
                            "The stress runner requires RADIANCE_CHUNK_PERF=1 for its quiet gate");
                var quiet =
                        Class.forName("com.radiance.audit.CityFrameComparisonProbe")
                                .getDeclaredMethod("quiet", java.util.Properties.class);
                quiet.setAccessible(true);
                if (!(boolean) quiet.invoke(null, state)) quietSince = 0;
                else if (quietSince == 0) quietSince = now;
                if (quietSince == 0 || now - quietSince < 10_000_000_000L) return;
                initialReady = true;
                phaseStart = changed = now;
            }
            if (phase == 1) {
                if (editTask != null && editTask.isDone()) {
                    editTask.join();
                    editTask = null;
                }
                nextEdit = phaseStart + 250_000_000L + edits * 500_000_000L;
                if (edits < 30 && editTask == null && now >= nextEdit) {
                    boolean place = (++edits & 1) != 0;
                    editTask =
                            mc.getSingleplayerServer()
                                    .submit(
                                            () ->
                                                    mc.getSingleplayerServer()
                                                            .overworld()
                                                            .setBlock(
                                                                    new BlockPos(8, 75, 8),
                                                                    (place
                                                                                    ? net.minecraft
                                                                                            .world
                                                                                            .level
                                                                                            .block
                                                                                            .Blocks
                                                                                            .STONE
                                                                                    : net.minecraft
                                                                                            .world
                                                                                            .level
                                                                                            .block
                                                                                            .Blocks
                                                                                            .AIR)
                                                                            .defaultBlockState(),
                                                                    3));
                }
            }
            long[] counters = LevititeWorldAdapter.counters();
            if (counters[2] != definitions) {
                definitions = counters[2];
                if (phase != 1) {
                    changed = now;
                    samples.clear();
                    cpuSamples.clear();
                    collectSamples.clear();
                    previous = previousCpu = 0;
                    sampledNanos = 0;
                }
            }
            if (counters[0] == 0
                    || now - changed < 3_000_000_000L
                    || now - phaseStart < 5_000_000_000L) return;
            long cpu = CPU.getCurrentThreadCpuTime();
            if (previous != 0) {
                samples.add((now - previous) / 1e6);
                sampledNanos += now - previous;
                cpuSamples.add((cpu - previousCpu) / 1e6);
                collectSamples.add(collectNanos / 1e6);
            }
            if (previous == 0) sampleEpoch = System.currentTimeMillis();
            previous = now;
            previousCpu = cpu;
            if (samples.size() < 30 || sampledNanos < 10_000_000_000L) return;
            if (phase == 1 && (edits != 30 || editTask != null)) return;
            double[] sorted = samples.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            var result = new LinkedHashMap<String, Object>();
            result.put(
                    "phase",
                    new String[] {
                                "world-still", "world-edits", "sublevel-still", "sublevel-deforming"
                            }
                            [phase]);
            result.put("frameCount", samples.size());
            result.put("p50Ms", sorted[sorted.length / 2]);
            result.put("p95Ms", sorted[(int) ((sorted.length - 1) * .95)]);
            result.put("p99Ms", sorted[(int) ((sorted.length - 1) * .99)]);
            result.put(
                    "meanMs",
                    samples.stream().mapToDouble(Double::doubleValue).average().orElseThrow());
            result.put("rawFrameMs", List.copyOf(samples));
            result.put("rawCpuMs", List.copyOf(cpuSamples));
            result.put("rawCollectMs", List.copyOf(collectSamples));
            result.put("startEpochMs", sampleEpoch);
            result.put("endEpochMs", System.currentTimeMillis());
            result.put("editCount", edits);
            result.put("counters", counters);
            result.put("definitionsCreated", counters[2] - phaseDefinitions);
            result.put(
                    "elapsedToStableWindowSeconds", (now - phaseStart) / 1e9 - sampledNanos / 1e9);
            result.put(
                    "javaHeapUsed",
                    Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory());
            results.add(result);
            if (phase == 3) {
                finish(mc, true, null);
                return;
            }
            if (phase == 1)
                pending =
                        mc.getSingleplayerServer()
                                .submit(
                                        () -> {
                                            var blocks = new ArrayList<BlockPos>();
                                            mc.getSingleplayerServer()
                                                    .overworld()
                                                    .setBlock(
                                                            new BlockPos(8, 75, 8),
                                                            net.minecraft.world.level.block.Blocks
                                                                    .AIR
                                                                    .defaultBlockState(),
                                                            3);
                                            for (int z = 0; z < 32; z++)
                                                for (int x = 0; x < 32; x++)
                                                    blocks.add(new BlockPos(x, 72, z));
                                            SubLevelAssemblyHelper.assembleBlocks(
                                                    mc.getSingleplayerServer().overworld(),
                                                    new BlockPos(16, 72, 16),
                                                    blocks,
                                                    new BoundingBox3i(0, 72, 0, 31, 72, 31));
                                        });
            phase++;
            phaseDefinitions = counters[2];
            samples.clear();
            cpuSamples.clear();
            collectSamples.clear();
            previous = previousCpu = 0;
            sampledNanos = 0;
            phaseStart = changed = now;
        } catch (Throwable failure) {
            finish(mc, false, failure);
        }
    }

    public static void prepareFrame(net.minecraft.client.multiplayer.ClientLevel level) {
        collectBegin = System.nanoTime();
        if (!"levitite-stress".equals(System.getProperty("radiance.audit.worldMaterials134"))
                || phase < 2
                || done) return;
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return;
        for (var source : container.getAllSubLevels())
            if (source instanceof ClientSubLevel client) {
                var manager =
                        LevititeShaderManager.managers.computeIfAbsent(
                                client, k -> new LevititeShaderManager());
                var state = (LevititeMotionAccessor) manager;
                state.radiance$linear().set(phase == 3 ? .12 : 0, phase == 3 ? .025 : 0, 0);
                state.radiance$angular()
                        .set(phase == 3 ? .01 : 0, phase == 3 ? .025 : 0, phase == 3 ? .005 : 0);
                state.radiance$previousLinear().set(state.radiance$linear());
                state.radiance$previousAngular().set(state.radiance$angular());
            }
    }

    public static void finishFrame() {
        collectNanos = System.nanoTime() - collectBegin;
    }

    private static void finish(Minecraft mc, boolean passed, Throwable failure) {
        done = true;
        var result = new LinkedHashMap<String, Object>();
        result.put("passed", passed);
        result.put("blocks", 1024);
        result.put("phases", results);
        result.put("sourceVelocityInjected", true);
        result.put(
                "scope",
                "Frame intervals of controlled effects, not simulation throughput or section compilation alone");
        if (failure != null) {
            result.put("error", failure.toString());
            com.mojang.logging.LogUtils.getLogger().error("Levitite scale check", failure);
        }
        try {
            Files.writeString(
                    Path.of(System.getProperty("validation.result"))
                            .resolve("world-materials.json"),
                    new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(result)
                            + "\n");
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        mc.stop();
    }
}
