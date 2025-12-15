package com.roncatech.libvcat.dav1d;

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.roncatech.libvcat.decoder.VcatDecoderPlugin;

/**
 * VCAT AV1 plugin backed by dav1d.
 * ID: vcat.dav1d
 */
@UnstableApi
public final class VcatDav1dPlugin implements VcatDecoderPlugin {

    @Override
    public String getId() {
        return "vcat.dav1d";
    }

    @Override
    public String getDisplayName() {
        return getId() + "-" + getVersion();
    }

    @Override
    public String getVersion() {
        // Bump as you ship new builds
        return NativeDav1d.dav1dGetVersion();
    }

    @Override
    public String getMimeType() {
        return "video/av01";
    }

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
        Dav1dAv1Provider dav1d = new Dav1dAv1Provider(threads, /* tileThreads = */ 4);
        if (dav1d.isAvailable(context)) {
            return dav1d.build(allowedJoiningTimeMs, eventHandler, eventListener);
        }
        throw new DecoderException("Unable to build 'vcat-dav1d' renderer");
    }
}
