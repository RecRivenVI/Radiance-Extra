package com.radiance.extra.compatibility.simulated;

import static org.junit.jupiter.api.Assertions.*;

import com.mojang.blaze3d.vertex.VertexBuffer;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

class DiagramSectionMeshesTest {
    @Test
    void retiresRemovedSectionsOnceAndKeepsSelectedResourcesAlive() throws Exception {
        var cache = new DiagramSectionMeshes();
        var first = section();
        var second = section();
        var retired = buffer(false);
        var retained = buffer(false);
        entries(cache).put(first, entry(retired));
        entries(cache).put(second, entry(retained));

        cache.retain(Set.of(second));
        assertEquals(1, retired.closed);
        assertEquals(0, retained.closed);
        assertEquals(Set.of(second), entries(cache).keySet());
        cache.retain(Set.of(second));
        assertEquals(1, retired.closed);
        cache.close();
        cache.close();
        assertEquals(1, retained.closed);
    }

    @Test
    void retiringSeveralSectionsFinishesCleanupAndPropagatesTheFailure() throws Exception {
        var cache = new DiagramSectionMeshes();
        var failed = buffer(true);
        var other = buffer(false);
        entries(cache).put(section(), entry(failed));
        entries(cache).put(section(), entry(other));
        assertThrows(IllegalStateException.class, () -> cache.retain(Set.of()));
        assertTrue(entries(cache).isEmpty());
        assertEquals(1, failed.closed);
        assertEquals(1, other.closed);
        cache.close();
        assertEquals(1, failed.closed);
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> entries(DiagramSectionMeshes cache) throws Exception {
        var field = DiagramSectionMeshes.class.getDeclaredField("entries");
        field.setAccessible(true);
        return (Map<Object, Object>) field.get(cache);
    }

    @SuppressWarnings("unchecked")
    private static Object entry(TrackingBuffer buffer) throws Exception {
        var type = Class.forName(DiagramSectionMeshes.class.getName() + "$Entry");
        var constructor = type.getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        Object entry = constructor.newInstance(null, null);
        var field = type.getDeclaredField("buffers");
        field.setAccessible(true);
        ((Map<Object, Object>) field.get(entry)).put(null, buffer);
        return entry;
    }

    private static SectionRenderDispatcher.RenderSection section() throws Exception {
        return (SectionRenderDispatcher.RenderSection)
                unsafe().allocateInstance(SectionRenderDispatcher.RenderSection.class);
    }

    private static TrackingBuffer buffer(boolean fail) throws Exception {
        var buffer = (TrackingBuffer) unsafe().allocateInstance(TrackingBuffer.class);
        buffer.fail = fail;
        return buffer;
    }

    private static Unsafe unsafe() throws Exception {
        var field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    // No GL context: allocate the fake without invoking the native buffer constructor.
    private static class TrackingBuffer extends VertexBuffer {
        int closed;
        boolean fail;

        private TrackingBuffer() {
            super(Usage.STATIC);
        }

        @Override
        public void close() {
            closed++;
            if (fail) throw new IllegalStateException("release failed");
        }
    }
}
