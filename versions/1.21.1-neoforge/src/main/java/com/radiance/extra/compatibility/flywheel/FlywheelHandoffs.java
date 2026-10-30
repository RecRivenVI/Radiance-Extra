package com.radiance.extra.compatibility.flywheel;

import com.radiance.api.render.SectionHandoff;
import com.radiance.extra.compatibility.sable.SableSubLevelBridge;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Visual ownership is captured when the source constructs a visual, not inferred from meshes. */
public final class FlywheelHandoffs {
    private static final ThreadLocal<Owner> CURRENT = new ThreadLocal<>();
    private static final Map<BlockEntity, Owner> OWNERS = new IdentityHashMap<>();
    private static final Set<Transfer> TRANSFERS =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private static Predicate<BlockEntity> visualizable = entity -> false;
    private static long started, published;
    private static long retainedInstances, stagedInstances;
    private static final Map<net.minecraft.world.level.Level, Long> COMPLETED = new WeakHashMap<>();
    private static long completionRevision;

    private FlywheelHandoffs() {}

    static void visualizable(Predicate<BlockEntity> predicate) {
        visualizable = predicate;
    }

    public static Scope constructing(BlockEntity entity) {
        Owner previous = CURRENT.get();
        Owner owner;
        synchronized (OWNERS) {
            owner = OWNERS.computeIfAbsent(entity, Owner::new);
            owner.constructed = false;
            for (var transfer : TRANSFERS) transfer.enroll(owner);
        }
        CURRENT.set(owner);
        return new Scope(previous, owner);
    }

    static Owner current() {
        return CURRENT.get();
    }

    static void completed(net.minecraft.world.level.Level level) {
        synchronized (OWNERS) {
            COMPLETED.put(level, ++completionRevision);
        }
    }

    public static void blockEntityChanged(BlockEntity entity) {
        synchronized (OWNERS) {
            for (var transfer : TRANSFERS)
                if (entity.getLevel() == transfer.level
                        && transfer.destination.contains(entity.getBlockPos()))
                    transfer.observed.add(entity.getBlockPos().immutable());
        }
    }

    /** Pin the old publication as soon as a split is announced, before child data arrives. */
    public static SectionHandoff.Participant retainSource(
            net.minecraft.world.level.Level level,
            Object source,
            java.util.function.BooleanSupplier ready) {
        Object token = new Object();
        List<Owner> owners;
        synchronized (OWNERS) {
            owners =
                    OWNERS.values().stream()
                            .filter(
                                    owner ->
                                            owner.entity.getLevel() == level
                                                    && owner.source == source)
                            .toList();
            owners.forEach(owner -> owner.hold(token, true));
        }
        return new SectionHandoff.Participant() {
            boolean closed;

            public boolean ready() {
                return ready.getAsBoolean();
            }

            public void publish() {
                release(true);
            }

            public void cancel() {
                release(false);
            }

            private void release(boolean publish) {
                if (closed) return;
                closed = true;
                owners.forEach(owner -> owner.release(token));
                for (var owner : owners)
                    for (var member : owner.snapshot()) {
                        if (publish) member.publish();
                        else member.cancel();
                    }
            }
        };
    }

    public static final class Scope implements AutoCloseable {
        private final Owner previous, owner;
        private boolean completed;

        private Scope(Owner previous, Owner owner) {
            this.previous = previous;
            this.owner = owner;
        }

        public void completed() {
            owner.constructed = true;
            completed = true;
        }

        public void close() {
            CURRENT.set(previous);
            // A throwing constructor must not acknowledge a replacement that never existed.
            if (!completed) owner.constructed = false;
        }
    }

    interface Member {
        boolean prepared();

        default boolean present() {
            return false;
        }

        void publish();

        void cancel();
    }

    static final class Owner {
        final BlockEntity entity;
        final Object source;
        final BlockPos position;
        final Set<Member> members = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Object> oldHolds = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Object> newHolds = Collections.newSetFromMap(new IdentityHashMap<>());
        volatile boolean constructed;

        Owner(BlockEntity entity) {
            this(entity, SableSubLevelBridge.blockSource(entity), entity.getBlockPos().immutable());
        }

        Owner(BlockEntity entity, Object source, BlockPos position) {
            this.entity = entity;
            this.source = source;
            this.position = position;
        }

        synchronized void add(Member member) {
            members.add(member);
        }

        synchronized void remove(Member member) {
            members.remove(member);
        }

        synchronized boolean retain() {
            return !oldHolds.isEmpty();
        }

        synchronized boolean stage() {
            return !newHolds.isEmpty();
        }

        synchronized List<Member> snapshot() {
            return List.copyOf(members);
        }

        synchronized void hold(Object transfer, boolean old) {
            (old ? oldHolds : newHolds).add(transfer);
        }

        synchronized void release(Object transfer) {
            oldHolds.remove(transfer);
            newHolds.remove(transfer);
        }
    }

    /** Both sides use actual source identity and a lattice position mapping supplied by Sable. */
    public static SectionHandoff.Participant transfer(
            net.minecraft.world.level.Level level,
            Object from,
            Object to,
            Function<BlockPos, BlockPos> mapping,
            Collection<BlockPos> destination,
            Function<BlockPos, BlockEntity> lookup) {
        var transfer = new Transfer(level, from, to, mapping, Set.copyOf(destination), lookup);
        synchronized (OWNERS) {
            TRANSFERS.add(transfer);
            for (var owner : OWNERS.values()) transfer.enroll(owner);
        }
        started++;
        return transfer;
    }

    private static final class Transfer implements SectionHandoff.Participant {
        final net.minecraft.world.level.Level level;
        final Object from, to;
        final Function<BlockPos, BlockPos> mapping;
        final Set<BlockPos> destination;
        final Function<BlockPos, BlockEntity> lookup;
        final Set<Owner> held = Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<Owner, BlockPos> expected = new IdentityHashMap<>();
        final Set<BlockPos> observed = new HashSet<>();
        boolean closed;
        final long beforeReconciliation;

        Transfer(
                net.minecraft.world.level.Level level,
                Object from,
                Object to,
                Function<BlockPos, BlockPos> mapping,
                Set<BlockPos> destination,
                Function<BlockPos, BlockEntity> lookup) {
            this.level = level;
            this.from = from;
            this.to = to;
            this.mapping = mapping;
            this.destination = destination;
            this.lookup = lookup;
            synchronized (OWNERS) {
                beforeReconciliation = COMPLETED.getOrDefault(level, 0L);
            }
            for (var position : destination)
                if (lookup.apply(position) != null) observed.add(position);
        }

        // Called under OWNERS only; never calls an engine while holding the registry lock.
        void enroll(Owner owner) {
            if (closed || owner.entity.getLevel() != level) return;
            if (owner.source == from) {
                BlockPos target = mapping.apply(owner.position);
                if (target != null && destination.contains(target)) {
                    owner.hold(this, true);
                    held.add(owner);
                    expected.put(owner, target);
                }
            }
            if (owner.source == to && destination.contains(owner.position)) {
                owner.hold(this, false);
                held.add(owner);
            }
        }

        public boolean ready() {
            List<Map.Entry<Owner, BlockPos>> replacements;
            synchronized (OWNERS) {
                replacements =
                        expected.entrySet().stream()
                                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                                .toList();
            }
            for (var replacement : replacements) {
                if (replacement.getKey().snapshot().isEmpty()) continue;
                BlockEntity entity = lookup.apply(replacement.getValue());
                if (entity == null) {
                    // Deletion need not create a replacement. Only an observed removal or a
                    // completed source reconciliation can acknowledge loaded absence;
                    // unavailable chunk data is never treated as an empty replacement.
                    boolean wasObserved;
                    synchronized (OWNERS) {
                        wasObserved =
                                observed.contains(replacement.getValue())
                                        || COMPLETED.getOrDefault(level, 0L) > beforeReconciliation;
                    }
                    if (wasObserved
                            && level.hasChunkAt(replacement.getValue())
                            && !level.getBlockState(replacement.getValue()).hasBlockEntity())
                        continue;
                    return false;
                }
                if (!visualizable.test(entity)) continue;
                Owner next;
                synchronized (OWNERS) {
                    next = OWNERS.get(entity);
                }
                if (next == null || !next.constructed) return false;
                for (var member : next.snapshot()) if (!member.prepared()) return false;
            }
            // New-only visuals also must have finished model preparation before terrain switches.
            for (var owner : snapshot())
                if (owner.source == to) {
                    if (!owner.constructed) return false;
                    for (var member : owner.snapshot()) if (!member.prepared()) return false;
                }
            return true;
        }

        private List<Owner> snapshot() {
            synchronized (OWNERS) {
                return List.copyOf(held);
            }
        }

        public void publish() {
            release(true);
            published++;
        }

        public void cancel() {
            release(false);
        }

        private void release(boolean publish) {
            List<Owner> owners;
            synchronized (OWNERS) {
                if (closed) return;
                closed = true;
                owners = List.copyOf(held);
                owners.forEach(owner -> owner.release(this));
                TRANSFERS.remove(this);
                held.clear();
                expected.clear();
            }
            // Publish only outside OWNERS: visual workers take engine/instancer locks.
            for (var owner : owners)
                for (var member : owner.snapshot()) {
                    if (publish) member.publish();
                    else member.cancel();
                }
        }
    }

    static void prune() {
        synchronized (OWNERS) {
            OWNERS.values()
                    .removeIf(
                            owner ->
                                    owner.entity.isRemoved()
                                            && owner.snapshot().isEmpty()
                                            && !owner.retain()
                                            && !owner.stage());
        }
    }

    static synchronized void retainedInstance() {
        retainedInstances++;
    }

    static synchronized void stagedInstance() {
        stagedInstances++;
    }

    public static long[] counters() {
        synchronized (OWNERS) {
            long members = 0, present = 0;
            for (var owner : OWNERS.values())
                for (var member : owner.snapshot()) {
                    members++;
                    if (member.present()) present++;
                }
            return new long[] {
                started,
                published,
                TRANSFERS.size(),
                OWNERS.size(),
                retainedInstances,
                stagedInstances,
                members,
                present
            };
        }
    }

    public static void close() {
        List<Transfer> transfers;
        synchronized (OWNERS) {
            transfers = List.copyOf(TRANSFERS);
        }
        transfers.forEach(Transfer::cancel);
        synchronized (OWNERS) {
            OWNERS.clear();
            COMPLETED.clear();
        }
    }
}
