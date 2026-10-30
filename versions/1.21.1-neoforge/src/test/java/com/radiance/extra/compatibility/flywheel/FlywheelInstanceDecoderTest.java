package com.radiance.extra.compatibility.flywheel;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.*;
import org.joml.*;
import org.junit.jupiter.api.Test;

class FlywheelInstanceDecoderTest {
    private static ByteBuffer input(int bytes) {
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
    }

    @Test
    void allSourceLayoutsProduceTheSameVersionedCanonicalPacketAndKeepTheirDistinctNormals() {
        for (var adapter : FlywheelInstanceAdapter.values()) {
            ByteBuffer src = input(112),
                    dst = input(com.radiance.render.contract.RenderAbi.CANONICAL_INSTANCE_BYTES);
            switch (adapter) {
                case TRANSFORMED, POSED -> new Matrix4f().translation(2, 3, 4).get(12, src);
                case FLUID, SCROLLING_TRANSFORMED ->
                        new Matrix4f().translation(2, 3, 4).get(0, src);
                case ORIENTED -> {
                    src.putFloat(48, 1);
                }
                case ROTATING -> {
                    src.putFloat(24, 1);
                    src.put(49, (byte) 127);
                    src.putFloat(40, 96).putFloat(44, 30);
                }
                case SCROLLING -> {
                    src.putFloat(36, 1);
                }
                case SHADOW -> {
                    src.putFloat(20, 2).putFloat(24, 3);
                }
            }
            if (adapter == FlywheelInstanceAdapter.POSED) new Matrix3f().scale(2).get(76, src);
            if (adapter == FlywheelInstanceAdapter.FLUID)
                src.putFloat(76, 0).putFloat(80, .5f).putFloat(84, .25f);
            if (adapter == FlywheelInstanceAdapter.SCROLLING)
                src.putFloat(40, .5f).putFloat(56, 2).putFloat(64, .75f);
            var packet = FlywheelInstanceDecoder.decode(adapter, src, dst).bytes();
            assertEquals(272, packet.remaining());
            assertTrue(Float.isFinite(packet.getFloat(64)), adapter.name());
            if (adapter == FlywheelInstanceAdapter.POSED) assertEquals(2, packet.getFloat(64));
            if (adapter == FlywheelInstanceAdapter.FLUID) {
                assertEquals(0, packet.getFloat(20));
                assertEquals(1, packet.getFloat(80));
                assertEquals(.5f, packet.getFloat(140));
            }
            if (adapter == FlywheelInstanceAdapter.ROTATING) {
                assertEquals(1, packet.getInt(192));
                assertEquals(96, packet.getFloat(208));
                assertEquals(30, packet.getFloat(212));
            }
            if (adapter == FlywheelInstanceAdapter.SCROLLING) {
                assertEquals(2, packet.getInt(192));
                assertEquals(.5f, packet.getFloat(228));
                assertEquals(2, packet.getFloat(236));
                assertEquals(.75f, packet.getFloat(244));
            }
        }
    }
}
