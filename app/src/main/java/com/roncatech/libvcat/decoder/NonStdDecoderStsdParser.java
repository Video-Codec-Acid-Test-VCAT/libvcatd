package com.roncatech.libvcat.decoder;

import androidx.media3.common.util.UnstableApi;

public interface NonStdDecoderStsdParser {

    int sampleEntry4ccCode();
    int codecConfiguration4ccCode();
    String mimeType();

    @UnstableApi
    VideoConfiguration parseStsd(byte[] data);
}
