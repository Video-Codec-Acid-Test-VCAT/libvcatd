package com.roncatech.libvcat.decoder;

import com.google.android.exoplayer2.util.ParsableByteArray;

public interface NonStdDecoderStsdParser {

    int sampleEntry4ccCode();
    int codecConfiguration4ccCode();
    String mimeType();

    VideoConfiguration parseStsd(byte[] data);
}
