package com.radiance.extra.audit;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class VoxyWorldProbeTest {
    @Test
    void unchangedCountersCannotPassSourceMutation() {
        long[] counters = new long[18];
        counters[2] = 11;
        counters[4] = 372;
        assertFalse(VoxyWorldProbe.sourceUpdateReady(counters, 372, 11));
        counters[4]++;
        assertFalse(VoxyWorldProbe.sourceUpdateReady(counters, 372, 11));
        counters[2]++;
        assertTrue(VoxyWorldProbe.sourceUpdateReady(counters, 372, 11));
        counters[4]--;
        assertFalse(VoxyWorldProbe.sourceUpdateReady(counters, 372, 11));
    }

    @Test
    void sourceCoverageFollowsTheActuallyLoadedFixtureStages() {
        long[] counters = new long[18];
        counters[1] = 1;
        counters[2] = 8;
        counters[4] = 1;
        counters[5] = 1;
        counters[13] = 1;
        counters[14] = 2;
        counters[15] = 1;
        counters[16] = 3;
        counters[17] = 1;
        assertTrue(VoxyWorldProbe.sourceCoverageReady("vanilla-initial", counters));
        assertTrue(VoxyWorldProbe.sceneReady(0, counters));
        counters[5] = 0;
        assertTrue(VoxyWorldProbe.sceneReady(0, counters));
        assertFalse(VoxyWorldProbe.sceneReady(3, counters));
        counters[5] = 1;
        assertTrue(VoxyWorldProbe.sceneReady(0, java.util.Arrays.copyOf(counters, 20)));
        assertFalse(VoxyWorldProbe.sceneReady(3, counters));
        assertFalse(VoxyWorldProbe.sourceCoverageReady("far-source-ingested", counters));
        counters[17] = 2;
        assertTrue(VoxyWorldProbe.sourceCoverageReady("far-source-ingested", counters));
        assertTrue(VoxyWorldProbe.sceneReady(3, counters));
        counters[5] = 0;
        assertFalse(VoxyWorldProbe.sceneReady(3, counters));
        counters[5] = 1;
        counters[13] = 0;
        assertFalse(VoxyWorldProbe.sourceCoverageReady("vanilla-initial", counters));
        assertFalse(VoxyWorldProbe.sourceCoverageReady("far-source-ingested", counters));
        assertFalse(VoxyWorldProbe.sourceCoverageReady("vanilla-initial", new long[7]));
    }

    @Test
    void disconnectProgressFramesCannotRecursivelyDisconnectAgain() throws Exception {
        var phase = VoxyWorldProbe.class.getDeclaredField("step");
        phase.setAccessible(true);
        int previous = phase.getInt(null);
        try {
            phase.setInt(null, 5);
            var calls = new AtomicInteger();
            assertTrue(
                    VoxyWorldProbe.disconnectOnce(
                            () -> {
                                calls.incrementAndGet();
                                assertFalse(
                                        VoxyWorldProbe.disconnectOnce(
                                                () -> fail("Recursive disconnect")));
                            }));
            assertEquals(1, calls.get());
            assertEquals(6, phase.getInt(null));
            assertFalse(VoxyWorldProbe.disconnectOnce(() -> fail("Repeated disconnect")));
        } finally {
            phase.setInt(null, previous);
        }
    }
}
