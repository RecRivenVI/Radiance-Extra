package com.radiance.extra.compatibility.veil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BiConsumer;
import org.lwjgl.system.MemoryUtil;

/** Owns CPU serialization storage; each draw still receives an independent uniform copy. */
public final class VeilBlockSnapshot implements AutoCloseable {
    private ByteBuffer bytes;
    private int size = -1;
    private boolean valid;

    public void write(
            Object value,
            int size,
            boolean dirty,
            BiConsumer<Object, ByteBuffer> serializer,
            ByteBuffer destination) {
        if (size < 0 || destination.remaining() > size) {
            throw new IllegalArgumentException("Invalid Veil uniform block range");
        }
        if (bytes == null || bytes.capacity() < size) {
            ByteBuffer replacement =
                    MemoryUtil.memAlloc(Math.max(1, size)).order(ByteOrder.nativeOrder());
            close();
            bytes = replacement;
        }
        if (dirty || !valid || this.size != size) {
            valid = false;
            bytes.clear().limit(size);
            MemoryUtil.memSet(MemoryUtil.memAddress(bytes), 0, size);
            if (value != null) serializer.accept(value, bytes);
            bytes.clear().limit(size);
            this.size = size;
            valid = true;
        }
        // Never let a deferred draw retain the mutable serialization cache.
        destination.put(bytes.slice(0, destination.remaining()));
    }

    @Override
    public void close() {
        if (bytes != null) MemoryUtil.memFree(bytes);
        bytes = null;
        size = -1;
        valid = false;
    }
}
