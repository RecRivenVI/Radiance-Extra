package com.radiance.extra.compatibility.sable;

import com.radiance.api.render.RenderSpace;
import com.radiance.api.render.SectionHandoff;
import com.radiance.api.render.SectionScene;
import com.radiance.extra.compatibility.flywheel.FlywheelHandoffs;
import dev.ryanhcode.sable.api.sublevel.*;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaterniond;
import org.joml.Vector3d;

/** Persistent source membership, populated by Sable's observer and its actual mutation seams. */
public final class SableSceneRegistry {
    private static ClientLevel level;
    private static ClientSubLevelContainer container;
    private static final Map<ClientSubLevel, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<Object, Entry> PLOTS = new IdentityHashMap<>();
    private static final Map<Long, Entry> COLUMNS = new HashMap<>();
    private static final ConcurrentLinkedQueue<Runnable> CHANGES = new ConcurrentLinkedQueue<>();
    private static final Set<ClientSubLevel> SECTION_CHANGES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<Object> PLOT_CHANGES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final Set<Long> COLUMN_CHANGES =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static List<ClientSubLevel> members = List.of();
    private static final Map<ClientSubLevel, ClientSubLevel> SPLITS = new IdentityHashMap<>();
    private static final Map<ClientSubLevel, MergeLink> MERGES = new IdentityHashMap<>();
    private static long unmappedTransfers;
    private static long mergedTransfers;
    private static final ArrayDeque<Map<String, Object>> TRANSFER_EVENTS = new ArrayDeque<>();
    private static long handoffsStarted, handoffsCompleted;

    private SableSceneRegistry() {}

    static Collection<ClientSubLevel> members(ClientLevel world) {
        attach(world);
        drain();
        return members;
    }

    private static void attach(ClientLevel world) {
        var next = SubLevelContainer.getContainer(world);
        if (level == world && container == next) return;
        close();
        level = world;
        container = next;
        if (container == null) return;
        ClientSubLevelContainer observed = container;
        observed.addObserver(
                new SubLevelObserver() {
                    @Override
                    public void onSubLevelAdded(SubLevel subLevel) {
                        if (subLevel instanceof ClientSubLevel client)
                            CHANGES.add(
                                    () -> {
                                        if (container == observed) add(client);
                                    });
                    }

                    @Override
                    public void onSubLevelRemoved(SubLevel subLevel, SubLevelRemovalReason reason) {
                        if (subLevel instanceof ClientSubLevel client)
                            CHANGES.add(
                                    () -> {
                                        if (container == observed) remove(client, reason);
                                    });
                    }
                });
        for (SubLevel subLevel : observed.getAllSubLevels())
            if (subLevel instanceof ClientSubLevel client) add(client);
    }

    private static void add(ClientSubLevel subLevel) {
        if (ENTRIES.containsKey(subLevel)) return;
        var entry = new Entry(subLevel);
        ENTRIES.put(subLevel, entry);
        PLOTS.put(subLevel.getPlot(), entry);
        members = ENTRIES.values().stream().filter(e -> !e.removed).map(e -> e.source).toList();
    }

    private static void remove(ClientSubLevel subLevel, SubLevelRemovalReason reason) {
        Entry entry = ENTRIES.get(subLevel);
        if (entry == null) return;
        SPLITS.remove(subLevel);
        MergeLink merge = MERGES.remove(subLevel);
        MERGES.values().removeIf(link -> link.target == subLevel);
        PLOTS.remove(subLevel.getPlot());
        COLUMNS.values().removeIf(value -> value == entry);
        if (reason == SubLevelRemovalReason.UNLOADED) {
            SPLITS.values().removeIf(parent -> parent == subLevel);
            ENTRIES.remove(subLevel);
            entry.close();
            members = ENTRIES.values().stream().filter(e -> !e.removed).map(e -> e.source).toList();
            return;
        }
        entry.removed = true;
        entry.beginHandoff();
        entry.includeLogicalBounds();
        Entry target = merge == null ? null : ENTRIES.get(merge.target);
        boolean received = target != null && !target.removed && merge.received();
        if (TRANSFER_EVENTS.size() == 32) TRANSFER_EVENTS.removeFirst();
        TRANSFER_EVENTS.add(
                Map.of(
                        "source",
                        sourceId(entry.source),
                        "target",
                        target == null ? "world" : sourceId(target.source),
                        "hasLink",
                        merge != null,
                        "received",
                        received,
                        "alignmentError",
                        merge == null ? -1D : merge.alignmentError(entry)));
        if (received) {
            mergedTransfers++;
            entry.target = target;
            target.sectionsDirty = target.blocksDirty = true;
            if (target.sections != null) entry.handoff.sections(target.sections);
            entry.transferInstances(target.source, merge);
        } else {
            entry.handoff.world(entry.worldMinimum, entry.worldMaximum);
            entry.transferInstances(null, null);
        }
        if (entry.sections != null) {
            entry.sections.close();
            entry.sections = null;
        }
        members = ENTRIES.values().stream().filter(e -> !e.removed).map(e -> e.source).toList();
    }

    private static void drain() {
        Runnable change;
        while ((change = CHANGES.poll()) != null) change.run();
        for (var source : List.copyOf(SECTION_CHANGES))
            if (SECTION_CHANGES.remove(source)) {
                var entry = ENTRIES.get(source);
                if (entry != null) entry.sectionsDirty = true;
            }
        for (var source : List.copyOf(PLOT_CHANGES))
            if (PLOT_CHANGES.remove(source)) {
                var entry = PLOTS.get(source);
                if (entry != null) entry.blocksDirty = true;
            }
        for (var source : List.copyOf(COLUMN_CHANGES))
            if (COLUMN_CHANGES.remove(source)) {
                var entry = COLUMNS.get(source);
                if (entry != null) entry.blocksDirty = true;
            }
    }

    public static void sectionsChanged(ClientSubLevel subLevel) {
        SECTION_CHANGES.add(subLevel);
    }

    public static void plotChanged(Object plot) {
        PLOT_CHANGES.add(plot);
    }

    public static void blockEntityChanged(BlockEntity entity) {
        if (entity.getLevel() != level) return;
        FlywheelHandoffs.blockEntityChanged(entity);
        long column =
                ChunkPos.asLong(entity.getBlockPos().getX() >> 4, entity.getBlockPos().getZ() >> 4);
        COLUMN_CHANGES.add(column);
    }

    static void update(ClientLevel world, float partialTick) {
        attach(world);
        drain();
        for (var entry : ENTRIES.values()) if (!entry.removed) entry.update(partialTick);
        for (var entry : ENTRIES.values())
            if (entry.target != null && entry.handoff != null) {
                if (entry.target.sections != null) entry.handoff.sections(entry.target.sections);
                for (var transfer : entry.target.handoffs) entry.handoff.join(transfer);
            }
        for (var iterator = SPLITS.entrySet().iterator(); iterator.hasNext(); ) {
            var relation = iterator.next();
            var child = relation.getKey();
            var parent = relation.getValue();
            var next = ENTRIES.get(child);
            var previous = ENTRIES.get(parent);
            if (next != null && previous != null && next.handoff != null) {
                if (previous.sections != null) next.handoff.sections(previous.sections);
                for (var transfer : previous.handoffs) next.handoff.join(transfer);
                iterator.remove();
            }
        }
        for (var entry : ENTRIES.values()) {
            if (entry.handoff != null) {
                entry.handoff.seal();
                entry.handoff = null;
            }
        }
        // Joined terrain groups and their visual callbacks must all be prepared and published
        // before selecting any local model. Entry order must not add a one-frame handoff gap.
        for (var entry : ENTRIES.values()) entry.handoffs.forEach(SectionHandoff::prepareCommit);
        var ready = new IdentityHashMap<Entry, Boolean>();
        for (var entry : ENTRIES.values()) {
            if (entry.handoffs.stream().anyMatch(SectionHandoff::cancelled)) {
                entry.handoffs.forEach(SectionHandoff::close);
                entry.handoffs.clear();
                entry.handoff = null;
                if (!entry.removed) entry.sectionsDirty = entry.blocksDirty = true;
                ready.put(entry, false);
                continue;
            }
            boolean complete = true;
            for (var transfer : entry.handoffs) complete &= transfer.tryCommit();
            ready.put(entry, complete);
        }
        for (var entry : List.copyOf(ENTRIES.values())) {
            if (ready.get(entry)) entry.commit();
            if (entry.removed && entry.handoffs.isEmpty()) {
                ENTRIES.remove(entry.source);
                entry.close();
            }
        }
        RenderSpace.flushUpdates();
    }

    public static void split(ClientSubLevel child, ClientSubLevel parent) {
        CHANGES.add(
                () -> {
                    SPLITS.put(child, parent);
                    var entry = ENTRIES.get(parent);
                    if (entry != null) {
                        entry.beginHandoff();
                        entry.handoff.participant(
                                SableRenderPublications.retainSource(
                                        level,
                                        parent,
                                        () -> {
                                            var next = ENTRIES.get(child);
                                            return next == null
                                                    ? !SPLITS.containsKey(child)
                                                    : next.removed
                                                            || (next.renderData != null
                                                                    && !next.sectionsDirty);
                                        }));
                    }
                });
    }

    /** The glue packet names the actual receiver; a merge is not necessarily a world return. */
    public static boolean mergeLink(BlockEntity glue, BlockPos partner) {
        if (!(glue.getLevel() instanceof ClientLevel client) || partner == null) return false;
        Object from = SableSubLevelBridge.blockSource(glue);
        var to = dev.ryanhcode.sable.Sable.HELPER.getContaining(client, partner);
        if (!(from instanceof ClientSubLevel source)
                || !(to instanceof ClientSubLevel target)
                || source == target) return false;
        var state = glue.getBlockState();
        var facing = net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING;
        if (!state.hasProperty(facing)) return false;
        var anchor = glue.getBlockPos().relative(state.getValue(facing).getOpposite());
        var sourceBlock = client.getBlockState(anchor).getBlock();
        CHANGES.add(
                () -> {
                    if (level == client && !source.isRemoved())
                        MERGES.put(
                                source,
                                new MergeLink(
                                        target,
                                        anchor.immutable(),
                                        partner.immutable(),
                                        sourceBlock));
                });
        return true;
    }

    private record MergeLink(
            ClientSubLevel target,
            BlockPos from,
            BlockPos to,
            net.minecraft.world.level.block.Block block) {
        boolean received() {
            // Glue identity plus the actual replacement is authoritative. An interpolation
            // pose is not an operation ID and must not redirect a merge to the world.
            return !block.defaultBlockState().isAir() && level.getBlockState(to).is(block);
        }

        double alignmentError(Entry entry) {
            var a = new Vector3d(from.getX() + .5, from.getY() + .5, from.getZ() + .5);
            var b = new Vector3d(to.getX() + .5, to.getY() + .5, to.getZ() + .5);
            entry.source.logicalPose().transformPosition(a);
            target.logicalPose().transformPosition(b);
            return a.distance(b);
        }
    }

    private static String sourceId(ClientSubLevel source) {
        return source.getUniqueId().toString();
    }

    public static List<Map<String, Object>> transferEvents() {
        return List.copyOf(TRANSFER_EVENTS);
    }

    private static Matrix4d logicalMatrix(ClientSubLevel source) {
        if (source == null) return new Matrix4d();
        var pose = source.logicalPose();
        return new Matrix4d()
                .translation(pose.position())
                .rotate(pose.orientation())
                .scale(pose.scale())
                .translate(new Vector3d(pose.rotationPoint()).negate());
    }

    public static long unmappedTransfers() {
        return unmappedTransfers;
    }

    public static long mergedTransfers() {
        return mergedTransfers;
    }

    public static int mergeLinks() {
        return MERGES.size();
    }

    public static void resourcesInvalidated() {
        for (var entry : ENTRIES.values()) {
            entry.handoffs.forEach(SectionHandoff::close);
            entry.handoffs.clear();
            entry.handoff = null;
            if (entry.single != null) {
                entry.single.close();
                entry.single = null;
            }
            if (entry.nextSingle != null) {
                entry.nextSingle.close();
                entry.nextSingle = null;
            }
            entry.renderData = null;
            entry.sectionsDirty = entry.blocksDirty = true;
        }
    }

    public static void drawSingles() {
        for (var entry : ENTRIES.values()) if (entry.single != null) entry.single.draw(entry.space);
    }

    public static long[] handoffCounters() {
        return new long[] {
            handoffsStarted,
            handoffsCompleted,
            ENTRIES.values().stream()
                    .filter(
                            e ->
                                    !e.handoffs.isEmpty()
                                            || (!e.removed
                                                    && (e.sectionsDirty
                                                            || SECTION_CHANGES.contains(e.source)
                                                            || !e.source.isFinalized()
                                                            || e.renderData
                                                                    != e.source.getRenderData())))
                    .count(),
            ENTRIES.values().stream().filter(e -> !e.removed).count()
        };
    }

    public static long[] singleModelCounters() {
        return SableSingleModel.counters();
    }

    static Entry entry(Object subLevel) {
        return ENTRIES.get(subLevel);
    }

    /** Borrowed parent space; source adapters retain only their own meshes, never this lifetime. */
    public record SharedSpace(RenderSpace space, Vector3d origin) {}

    public static SharedSpace sharedSpace(ClientSubLevel source) {
        var entry = ENTRIES.get(source);
        return entry == null || entry.removed || !entry.initialized
                ? null
                : new SharedSpace(entry.space, new Vector3d(entry.origin));
    }

    public static void close() {
        for (var entry : ENTRIES.values()) entry.close();
        ENTRIES.clear();
        PLOTS.clear();
        COLUMNS.clear();
        CHANGES.clear();
        SECTION_CHANGES.clear();
        PLOT_CHANGES.clear();
        COLUMN_CHANGES.clear();
        members = List.of();
        SPLITS.clear();
        MERGES.clear();
        TRANSFER_EVENTS.clear();
        container = null;
        level = null;
    }

    static final class Entry implements AutoCloseable {
        final ClientSubLevel source;
        final RenderSpace space = new RenderSpace();
        final Vector3d origin = new Vector3d();
        final Matrix3f basis = new Matrix3f();
        final Set<Long> columns = new HashSet<>();
        SectionScene sections;
        Object renderData;
        boolean initialized, sectionsDirty = true, blocksDirty = true;
        List<BlockEntity> blockEntities = List.of();
        SableSubLevelBridge.PoseComponents pose;
        SableSingleModel single, nextSingle;
        final List<SectionHandoff> handoffs = new ArrayList<>();
        SectionHandoff handoff;
        Entry target;
        boolean incomingTransfer, terrainTransfer;
        boolean removed, wantsSingle;
        BlockPos worldMinimum = BlockPos.ZERO, worldMaximum = BlockPos.ZERO;
        BlockPos plotMinimum, plotMaximum;

        Entry(ClientSubLevel source) {
            this.source = source;
        }

        void update(float partialTick) {
            var current = source.renderPose(partialTick);
            pose =
                    new SableSubLevelBridge.PoseComponents(
                            new Vector3d(current.position()),
                            new Quaterniond(current.orientation()).normalize(),
                            new Vector3d(current.rotationPoint()),
                            new Vector3d(current.scale()));
            if (!initialized) {
                origin.set(
                        Math.floor(pose.rotationPoint().x / 16) * 16,
                        Math.floor(pose.rotationPoint().y / 16) * 16,
                        Math.floor(pose.rotationPoint().z / 16) * 16);
                initialized = true;
            }
            var matrix =
                    new Matrix4d()
                            .translation(pose.position())
                            .rotate(pose.orientation())
                            .scale(pose.scale())
                            .translate(new Vector3d(origin).sub(pose.rotationPoint()));
            basis.set(new Matrix4f(matrix));
            var normal = new Matrix3f(basis).normal();
            if (!normal.isFinite()) normal.identity();
            space.pose(matrix, normal);
            var box = source.getPlot().getBoundingBox();
            if (box != null) {
                plotMinimum = new BlockPos(box.minX(), box.minY(), box.minZ());
                plotMaximum = new BlockPos(box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1);
                Vector3d minimum = new Vector3d(Double.POSITIVE_INFINITY),
                        maximum = new Vector3d(Double.NEGATIVE_INFINITY);
                for (int i = 0; i < 8; i++) {
                    var corner =
                            new Vector3d(
                                            (i & 1) == 0 ? box.minX() : box.maxX() + 1.0,
                                            (i & 2) == 0 ? box.minY() : box.maxY() + 1.0,
                                            (i & 4) == 0 ? box.minZ() : box.maxZ() + 1.0)
                                    .sub(origin);
                    matrix.transformPosition(corner);
                    minimum.min(corner);
                    maximum.max(corner);
                }
                worldMinimum = BlockPos.containing(minimum.x, minimum.y, minimum.z);
                worldMaximum = BlockPos.containing(maximum.x, maximum.y, maximum.z);
            }
            com.radiance.extra.compatibility.simulated.SimulatedWorldEffects.updateSectionColour(
                    space, level);
            Object data = source.getRenderData();
            if (!source.isFinalized() || data == null) return;
            boolean streamInitial = false;
            if (data != renderData) {
                boolean initial = renderData == null;
                beginHandoff();
                if (initial && !SPLITS.containsKey(source)) {
                    streamInitial = !handoff.worldChanges(worldMinimum, worldMaximum);
                    terrainTransfer = !streamInitial;
                }
                // Visual-only sources can transfer without any terrain BLAS. Their actual
                // outgoing owners still retain publications even when terrain can stream.
                incomingTransfer |= initial;
                renderData = data;
                sectionsDirty = true;
                blocksDirty = true;
            }
            if (sectionsDirty) {
                if (terrainTransfer && !handoffs.isEmpty()) beginHandoff();
                wantsSingle =
                        data
                                instanceof
                                dev.ryanhcode.sable.sublevel.render.vanilla
                                        .VanillaSingleSubLevelRenderData;
                if (data instanceof VanillaChunkedSubLevelRenderData chunked) {
                    if (sections == null) sections = new SectionScene(space, origin);
                    sections.members(chunked.allRenderSections());
                    // Membership/volume refresh is not a representation transfer. Explicit
                    // assembly/split/merge scopes already exist and still collect their members.
                    if (handoff != null && !streamInitial) handoff.sections(sections);
                } else if (sections != null) {
                    sections.close();
                    sections = null;
                }
                if (wantsSingle) {
                    beginHandoff();
                    if (nextSingle == null) nextSingle = new SableSingleModel();
                }
                sectionsDirty = false;
            }
            if (nextSingle != null) nextSingle.prepare(source, origin);
            else if (single != null && wantsSingle) single.prepare(source, origin);
            if (sections != null) sections.prepareFrame();
            if (blocksDirty) {
                columns.forEach(key -> COLUMNS.remove(key, this));
                columns.clear();
                var result = Collections.newSetFromMap(new IdentityHashMap<BlockEntity, Boolean>());
                for (var holder : source.getPlot().getLoadedChunks()) {
                    LevelChunk chunk = holder.getChunk();
                    if (chunk == null) continue;
                    long key = chunk.getPos().toLong();
                    columns.add(key);
                    COLUMNS.put(key, this);
                    result.addAll(chunk.getBlockEntities().values());
                }
                blockEntities = List.copyOf(result);
                blocksDirty = false;
            }
            if (incomingTransfer && handoff != null) {
                ClientSubLevel previous = SPLITS.get(source);
                var incomingMatrix = logicalMatrix(source).invert().mul(logicalMatrix(previous));
                var anchor = previous == null ? worldMinimum : previous.getPlot().getCenterBlock();
                var mapping = SableBlockMapping.aligned(incomingMatrix, anchor);
                if (mapping != null) {
                    handoff.participant(
                            SableRenderPublications.transfer(
                                    level,
                                    previous,
                                    source,
                                    mapping::apply,
                                    blockEntities.stream().map(BlockEntity::getBlockPos).toList(),
                                    position -> level.getBlockEntity(position)));
                } else unmappedTransfers++;
                incomingTransfer = false;
            }
        }

        void transferInstances(ClientSubLevel destination, MergeLink merge) {
            var matrix = logicalMatrix(destination).invert().mul(logicalMatrix(source));
            var anchor = merge == null ? source.getPlot().getCenterBlock() : merge.from;
            var mapping = SableBlockMapping.aligned(matrix, anchor);
            if (mapping == null) {
                unmappedTransfers++;
                return;
            }
            if (merge != null)
                mapping =
                        SableBlockMapping.anchored(
                                mapping.xx(),
                                mapping.xz(),
                                mapping.zx(),
                                mapping.zz(),
                                merge.from,
                                merge.to);
            var transform = mapping;
            handoff.participant(
                    SableRenderPublications.transfer(
                            level,
                            source,
                            destination,
                            transform::apply,
                            blockEntities.stream()
                                    .map(entity -> transform.apply(entity.getBlockPos()))
                                    .toList(),
                            position -> level.getBlockEntity(position)));
        }

        void includeLogicalBounds() {
            if (plotMinimum == null) return;
            // Disassembly can align the source during the final simulation tick; the last
            // interpolated image alone does not necessarily include its destination sections.
            var minimum =
                    new Vector3d(worldMinimum.getX(), worldMinimum.getY(), worldMinimum.getZ());
            var maximum =
                    new Vector3d(worldMaximum.getX(), worldMaximum.getY(), worldMaximum.getZ());
            for (int i = 0; i < 8; i++) {
                var corner =
                        new Vector3d(
                                (i & 1) == 0 ? plotMinimum.getX() : plotMaximum.getX(),
                                (i & 2) == 0 ? plotMinimum.getY() : plotMaximum.getY(),
                                (i & 4) == 0 ? plotMinimum.getZ() : plotMaximum.getZ());
                source.logicalPose().transformPosition(corner);
                minimum.min(corner);
                maximum.max(corner);
            }
            worldMinimum = BlockPos.containing(minimum.x, minimum.y, minimum.z);
            worldMaximum = BlockPos.containing(maximum.x, maximum.y, maximum.z);
        }

        void beginHandoff() {
            terrainTransfer = true;
            if (handoff != null) return;
            handoff = new SectionHandoff();
            for (var previous : handoffs) handoff.join(previous);
            handoffs.add(handoff);
            if (sections != null) handoff.sections(sections);
            handoffsStarted++;
        }

        void commit() {
            if (handoffs.isEmpty()) return;
            if (removed || !wantsSingle) {
                if (single != null) {
                    single.close();
                    single = null;
                }
                if (nextSingle != null) {
                    nextSingle.close();
                    nextSingle = null;
                }
            } else if (nextSingle != null) {
                if (single != null) single.close();
                single = nextSingle;
                nextSingle = null;
            }
            handoffs.forEach(SectionHandoff::close);
            handoffs.clear();
            terrainTransfer = false;
            handoffsCompleted++;
        }

        @Override
        public void close() {
            handoffs.forEach(SectionHandoff::close);
            handoffs.clear();
            if (single != null) single.close();
            if (nextSingle != null) nextSingle.close();
            if (sections != null) sections.close();
            space.close();
        }
    }
}
