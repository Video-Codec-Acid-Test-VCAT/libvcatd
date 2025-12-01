package com.roncatech.libvcat.vvdec;

import android.content.Context;
import android.os.Handler;

import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.VideoRendererEventListener;
import com.roncatech.libvcat.decoder.VcatDecoderPlugin;
import com.roncatech.libvcat.decoder.VideoConfiguration;
import com.roncatech.libvcat.decoder.NonStdDecoderStsdParser;

public class VcatVvcdecPlugin implements VcatDecoderPlugin, NonStdDecoderStsdParser {

    @Override public int sampleEntry4ccCode(){
        return Util.getIntegerCodeForString("vvc1");
    }

    @Override
    public int codecConfiguration4ccCode(){
        return Util.getIntegerCodeForString("vvcC");
    }

    @Override
    public String mimeType(){
        return mimeType;
    }

    @Override
    public VideoConfiguration parseStsd(byte[] data){
        return VvcVideoCfgParser.parseStsd(data);
    }

    @Override
    public String getId() {
        return "vcat.vvdec";
    }

    @Override
    public String getDisplayName() {
        return getId() + "-" + getVersion();
    }

    @Override
    public String getVersion() {
        // Bump as you ship new builds
        return NativeVvdec.vvdecGetVersion();
    }

    @Override
    public String getMimeType() {
        return mimeType;
    }

    public static final String mimeType = "video/vvc";

    @Override
    public java.util.List<String> getSupportedProfiles() {
        // You asked for lowercase "main"
        return java.util.Collections.singletonList("main");
    }

    @Override
    public Renderer createVideoRenderer(
            Context context,
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int threads
    ) throws DecoderException {
        // threads is required by SPI (>=1). tileThreads fixed to 4 per your note.
        VvdecProvider vvdec = new VvdecProvider(threads);
        if (vvdec.isAvailable(context)) {
            return vvdec.build(allowedJoiningTimeMs, eventHandler, eventListener);
        }
        throw new DecoderException("Unable to build 'vcat-vvdec' renderer");
    }
}
