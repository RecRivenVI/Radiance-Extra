package com.radiance.extra.compatibility.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class VoxyCaptureLockTest {
    @Test
    void receivedLightDoesNotInvalidateGeometryButBiomeAndStateDo() {
        for (int level = 0; level <= 4; level++) {
            int n = 16 >> level, start = 19, base = 3 | (7 << 5) | (9 << 10);
            long[] incoming = new long[start + n * n * n], target = new long[32768];
            java.util.Arrays.fill(incoming, 0xff00000008000000L);
            java.util.Arrays.fill(target, 0x1100000008000000L);
            assertFalse(VoxyCaptureLock.regionChanged(incoming, start, target, base, n));
            int last = start + n * n * n - 1;
            incoming[last] ^= 1L << 47; // The representative biome is still a material input.
            assertTrue(VoxyCaptureLock.regionChanged(incoming, start, target, base, n));
            incoming[last] ^= 1L << 47;
            incoming[last] ^= 1L << 27;
            assertTrue(VoxyCaptureLock.regionChanged(incoming, start, target, base, n));
        }
    }

    @Test
    void snapshotWaitsForWholeSourceUpdateAndOwnsItsArray() throws Exception {
        long[] input = {1, 1, 1, 1};
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        long before = VoxyCaptureLock.revision();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var writer =
                    executor.submit(
                            () -> {
                                VoxyCaptureLock.LOCK.writeLock().lock();
                                try {
                                    input[0] = 2;
                                    entered.countDown();
                                    if (!release.await(5, TimeUnit.SECONDS))
                                        throw new AssertionError("writer watchdog");
                                    for (int i = 1; i < input.length; i++) input[i] = 2;
                                    VoxyCaptureLock.changed();
                                } catch (InterruptedException e) {
                                    throw new AssertionError(e);
                                } finally {
                                    VoxyCaptureLock.LOCK.writeLock().unlock();
                                }
                            });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var readerEntered = new CountDownLatch(1);
            var reader =
                    executor.submit(
                            () -> {
                                readerEntered.countDown();
                                return VoxyCaptureLock.copy(input);
                            });
            try {
                assertTrue(readerEntered.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> reader.get(100, TimeUnit.MILLISECONDS));
            } finally {
                release.countDown();
            }
            writer.get(5, TimeUnit.SECONDS);
            long[] snapshot = reader.get(5, TimeUnit.SECONDS);
            assertArrayEquals(new long[] {2, 2, 2, 2}, snapshot);
            input[0] = 3;
            assertEquals(2, snapshot[0]);
            assertTrue(VoxyCaptureLock.revision() > before);
        } finally {
            release.countDown();
        }
    }

    @Test
    void unownedMutationIsRejected() {
        assertThrows(IllegalStateException.class, VoxyCaptureLock::changed);
    }
}
