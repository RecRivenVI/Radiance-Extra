package com.radiance.extra.compatibility.sable;

import com.radiance.api.render.BlockEntityPublications;
import com.radiance.api.render.SectionHandoff;
import com.radiance.extra.compatibility.flywheel.FlywheelHandoffs;
import java.util.Collection;
import java.util.function.*;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Terrain, instanced visuals and ordinary BERs all publish through the same source operation. */
final class SableRenderPublications {
    private SableRenderPublications() {}

    static SectionHandoff.Participant transfer(
            Level level,
            Object from,
            Object to,
            Function<BlockPos, BlockPos> mapping,
            Collection<BlockPos> destination,
            Function<BlockPos, BlockEntity> lookup) {
        return together(
                FlywheelHandoffs.transfer(level, from, to, mapping, destination, lookup),
                BlockEntityPublications.transfer(level, from, to, mapping, destination, lookup));
    }

    static SectionHandoff.Participant retainSource(
            Level level, Object source, BooleanSupplier ready) {
        return together(
                FlywheelHandoffs.retainSource(level, source, ready),
                BlockEntityPublications.retainSource(level, source, ready));
    }

    private static SectionHandoff.Participant together(
            SectionHandoff.Participant a, SectionHandoff.Participant b) {
        return new SectionHandoff.Participant() {
            public boolean ready() {
                return a.ready() & b.ready();
            }

            public void publish() {
                a.publish();
                b.publish();
            }

            public void cancel() {
                try {
                    a.cancel();
                } finally {
                    b.cancel();
                }
            }
        };
    }
}
