package com.roncatech.libvcat.decoder;

import android.content.Context;
import android.graphics.ImageDecoder;
import android.os.Handler;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import java.util.Collections;
import java.util.List;

/** Android SPI for single-codec decoder plugins (ExoPlayer video renderer). */
public interface VcatDecoderPlugin {
    String getId();               // e.g., "vvdec", "libdav1d-av1"
    String getDisplayName();      // e.g., "vvdec VVC Decoder"
    String getVersion();          // e.g., "1.0.0"

    /** Single codec MIME this plugin decodes, e.g. "video/vvc", "video/av01". */
    String getMimeType();

    /** Profiles supported for this codec, e.g. ["Main10"] or ["Main"]. */
    List<String> getSupportedProfiles();

    /**
     * Build and return the plugin’s ExoPlayer video Renderer.
     *
     * @param context Android context.
     * @param allowedJoiningTimeMs ExoPlayer join time.
     * @param eventHandler Handler for renderer callbacks.
     * @param eventListener Renderer event listener.
     * @param threads Required worker thread count (>= 1).
     * @return Renderer instance, or null if unavailable.
     */
    @UnstableApi
    Renderer createVideoRenderer(
            Context context,
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int threads
    ) throws DecoderException;

    /* --- helpers --- */

    default boolean supports(String mime) { return getMimeType().equals(mime); }

    default boolean supports(String mime, String profile) {
        return supports(mime) && getSupportedProfiles().contains(profile);
    }

    /** Optional extension hook (bitDepths, tiers, hdr formats, CPU features, etc.). */
    default List<String> getExtended(String key) { return Collections.emptyList(); }
}
