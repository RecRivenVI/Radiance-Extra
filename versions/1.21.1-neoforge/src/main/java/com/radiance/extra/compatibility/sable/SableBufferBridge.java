package com.radiance.extra.compatibility.sable;

import com.radiance.api.render.RenderResources;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.lwjgl.system.MemoryUtil;

/** CPU mirror and range-upload owner for Sable's persistent fancy-render buffers. */
public final class SableBufferBridge {

    private record Mirror(RenderResources.Buffer resource, byte[] bytes) {}

    private static final Map<Integer, Mirror> MIRRORS = new HashMap<>();

    private SableBufferBridge() {}

    public static synchronized int allocate() {
        var resource = RenderResources.buffer();
        MIRRORS.put(resource.id(), new Mirror(resource, new byte[0]));
        return resource.id();
    }

    public static synchronized void initialize(int id, int size, RenderResources.BufferUse usage) {
        if (size < 0) throw new IllegalArgumentException("Negative Sable buffer size");
        Mirror owned = require(id);
        byte[] previous = owned.bytes;
        byte[] replacement = new byte[size];
        System.arraycopy(previous, 0, replacement, 0, Math.min(previous.length, size));
        owned.resource.initialize(size, usage);
        if (size > 0) uploadWhole(owned.resource, replacement);
        MIRRORS.put(id, new Mirror(owned.resource, replacement));
    }

    public static synchronized void uploadRange(int id, long pointer, int size, int offset) {
        Mirror owned = require(id);
        byte[] mirror = owned.bytes;
        if (pointer == 0 || size < 0 || offset < 0 || (long) offset + size > mirror.length) {
            throw new IllegalArgumentException("Invalid Sable range upload");
        }
        if (size == 0) return;
        ByteBuffer source = MemoryUtil.memByteBuffer(pointer, size);
        owned.resource.upload(offset, source);
        source.get(mirror, offset, size);
    }

    public static synchronized void release(int id) {
        if (MIRRORS.containsKey(id)) {
            require(id).resource.close();
            MIRRORS.remove(id);
        }
    }

    static synchronized byte[] snapshotForTest(int id) {
        return require(id).bytes.clone();
    }

    private static Mirror require(int id) {
        Mirror mirror = MIRRORS.get(id);
        if (mirror == null) throw new IllegalStateException("Unknown Sable buffer " + id);
        return mirror;
    }

    private static void uploadWhole(RenderResources.Buffer resource, byte[] data) {
        ByteBuffer nativeData = MemoryUtil.memAlloc(data.length);
        try {
            nativeData.put(data).flip();
            resource.upload(0, nativeData);
        } finally {
            MemoryUtil.memFree(nativeData);
        }
    }
}
