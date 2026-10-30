package com.radiance.extra.compatibility.veil;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

class VeilBlockSnapshotTest {
    private static ByteBuffer target(int size) {
        return ByteBuffer.allocate(size).order(ByteOrder.nativeOrder());
    }

    @Test
    void unsafeSerializerCachesUnchangedDataAndEachDrawOwnsItsCopy() {
        Matrix4f matrix = new Matrix4f().rotateY(.7F).translate(2, 3, 4);
        var count = new AtomicInteger();
        BiConsumer<Object, ByteBuffer> serializer =
                (value, bytes) -> {
                    assertTrue(bytes.isDirect());
                    count.incrementAndGet();
                    ((Matrix4f) value).get(0, bytes);
                    ((Matrix4f) value).get(128, bytes);
                    bytes.position(16);
                };
        try (var snapshot = new VeilBlockSnapshot()) {
            var first = target(368);
            snapshot.write(matrix, 368, false, serializer, first);
            float original = first.getFloat(0);
            assertEquals(matrix.m32(), first.getFloat(184));
            assertEquals(0, first.getInt(364));
            matrix.identity();
            for (int i = 0; i < 20; i++) {
                var draw = target(368);
                snapshot.write(matrix, 368, false, serializer, draw);
                assertEquals(original, draw.getFloat(0));
            }
            assertEquals(1, count.get());
            var second = target(368);
            snapshot.write(matrix, 368, true, serializer, second);
            assertEquals(2, count.get());
            assertEquals(1F, second.getFloat(0));
            assertEquals(original, first.getFloat(0));
            snapshot.write(matrix, 512, false, serializer, target(512));
            assertEquals(3, count.get());
            snapshot.write(
                    matrix,
                    64,
                    false,
                    (value, bytes) -> ((Matrix4f) value).get(0, bytes),
                    target(64));
        }
    }

    @Test
    void nullPaddingFailureRetryAndCloseAreSafe() {
        var snapshot = new VeilBlockSnapshot();
        try {
            snapshot.write(null, 16, false, (value, bytes) -> fail(), target(16));
            assertThrows(
                    IllegalStateException.class,
                    () ->
                            snapshot.write(
                                    new Object(),
                                    16,
                                    true,
                                    (value, bytes) -> {
                                        bytes.putInt(42);
                                        throw new IllegalStateException("source");
                                    },
                                    target(16)));
            var recovered = target(16);
            snapshot.write(new Object(), 16, false, (value, bytes) -> bytes.putInt(7), recovered);
            assertEquals(7, recovered.getInt(0));
            assertEquals(0, recovered.getInt(4));
            snapshot.close();
            snapshot.write(null, 0, false, (value, bytes) -> fail(), target(0));
            assertThrows(
                    IllegalArgumentException.class,
                    () -> snapshot.write(null, 4, false, (value, bytes) -> fail(), target(8)));
        } finally {
            snapshot.close();
        }
    }
}
