package com.radiance.extra.compatibility.veil;

import java.nio.ByteBuffer;

public interface VeilShaderBlockData {

    int radiance$blockSize();

    void radiance$writeBlock(ByteBuffer destination);

    void radiance$releaseBlock();
}
