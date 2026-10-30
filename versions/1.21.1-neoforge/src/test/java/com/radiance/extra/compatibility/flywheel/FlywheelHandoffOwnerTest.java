package com.radiance.extra.compatibility.flywheel;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;

class FlywheelHandoffOwnerTest {
    @Test
    @SuppressWarnings("unchecked")
    void flywheelWorldCloseReleasesOwnersWithoutDependingOnSable() throws Exception {
        var field = FlywheelHandoffs.class.getDeclaredField("OWNERS");
        field.setAccessible(true);
        var owners = (java.util.Map<Object, Object>) field.get(null);
        owners.put(null, new FlywheelHandoffs.Owner(null, null, BlockPos.ZERO));
        try {
            assertEquals(1, FlywheelHandoffs.counters()[3]);
            new com.radiance.extra.integration.InstancingIntegration().worldClosed();
            assertEquals(0, FlywheelHandoffs.counters()[3]);
        } finally {
            owners.clear();
        }
    }

    @Test
    void overlappingTransfersCannotReleaseEachOthersModels() {
        var owner = new FlywheelHandoffs.Owner(null, new Object(), BlockPos.ZERO);
        Object a = new Object(), b = new Object();
        owner.hold(a, true);
        owner.hold(b, true);
        owner.hold(b, false);
        owner.release(a);
        assertTrue(owner.retain());
        assertTrue(owner.stage());
        owner.release(b);
        assertFalse(owner.retain());
        assertFalse(owner.stage());
    }

    @Test
    void membersHaveIdentityAndSnapshotsSurviveLaterDeletion() {
        var owner = new FlywheelHandoffs.Owner(null, null, BlockPos.ZERO);
        var member =
                new FlywheelHandoffs.Member() {
                    public boolean prepared() {
                        return false;
                    }

                    public void publish() {}

                    public void cancel() {}
                };
        owner.add(member);
        owner.add(member);
        var snapshot = owner.snapshot();
        owner.remove(member);
        assertEquals(1, snapshot.size());
        assertTrue(owner.snapshot().isEmpty());
    }
}
