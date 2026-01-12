/*
 * VCAT (Video Codec Acid Test)
 *
 * SPDX-FileCopyrightText: Copyright (C) 2020-2025 VCAT authors and RoncaTech
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This file is part of VCAT.
 *
 * VCAT is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VCAT is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VCAT. If not, see <https://www.gnu.org/licenses/gpl-3.0.html>.
 *
 * For proprietary/commercial use cases, a written GPL-3.0 waiver or
 * a separate commercial license is required from RoncaTech LLC.
 *
 * All VCAT artwork is owned exclusively by RoncaTech LLC. Use of VCAT logos
 * and artwork is permitted for the purpose of discussing, documenting,
 * or promoting VCAT itself. Any other use requires prior written permission
 * from RoncaTech LLC.
 *
 * Contact: legal@roncatech.com
 */
package com.roncatech.libvcat.vvdec;

import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.decoder.CryptoConfig; // ExoPlayer 2.x
import com.google.android.exoplayer2.decoder.Decoder;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

/**
 * Software VVC (H.266) renderer using vvdec via JNI, patterned after the dav1d path.
 */
public final class VvdecVideoRenderer extends DecoderVideoRenderer {

    private static final String TAG = "VvdecVideoRenderer";
    private static final int MAX_DROPPED_FRAMES_TO_NOTIFY = 50;

    private final int threads;

    private VvdecDecoder decoder;
    private Surface currentSurface;

    /**
     * @param allowedJoiningTimeMs same semantics as ExoPlayer’s DecoderVideoRenderer
     * @param eventHandler handler for video events
     * @param eventListener listener for video events
     * @param threads decoder worker thread count (>=1)
     */
    public VvdecVideoRenderer(
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int threads) {
        super(allowedJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_FRAMES_TO_NOTIFY);
        this.threads = Math.max(1, threads);
    }

    @Override public String getName() { return "VvdecVideoRenderer"; }

    @Override
    protected Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
    createDecoder(Format format, CryptoConfig cryptoConfig) throws VvdecDecoderException {
        VvdecLibrary.load();
        this.decoder = new VvdecDecoder(threads);
        if (this.currentSurface != null) {
            this.decoder.setOutputSurface(this.currentSurface);
        }
        // pass input format (for width/height reporting etc.)
        this.decoder.setInputFormat(format);
        return this.decoder;
    }

    @Override
    protected void renderOutputBufferToSurface(
            VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws VvdecDecoderException {
        if (decoder == null) {
            throw new VvdecDecoderException(
                    "Failed to render output buffer to surface: decoder is not initialized.");
        }
        if (surface != currentSurface) {
            currentSurface = surface;                 // may be null
            decoder.setOutputSurface(currentSurface); // JNI caches/releases ANativeWindow
        }

        decoder.renderToSurface((VvdecOutputBuffer) outputBuffer);
        outputBuffer.release();
    }

    private static String videoOutputModeStr(int outputMode){
        switch (outputMode){
            case C.VIDEO_OUTPUT_MODE_YUV: return "VIDEO_OUTPUT_MODE_YUV";
            case C.VIDEO_OUTPUT_MODE_SURFACE_YUV: return "VIDEO_OUTPUT_MODE_SURFACE_YUV";
            case C.VIDEO_OUTPUT_MODE_NONE: return "VIDEO_OUTPUT_MODE_NONE";
        }
        return "UNKNOWN";
    }

    @Override
    protected void setDecoderOutputMode(int outputMode) {
        Log.d(TAG, "setDecoderOutputMode=" + videoOutputModeStr(outputMode));
        switch (outputMode) {
            case C.VIDEO_OUTPUT_MODE_YUV:
            case C.VIDEO_OUTPUT_MODE_SURFACE_YUV:
            case C.VIDEO_OUTPUT_MODE_NONE:
                // vvdec always outputs planar YUV; surface blit is handled in JNI.
                return;
            default:
                throw new IllegalArgumentException(
                        "Surface output mode (" + videoOutputModeStr(outputMode) + ") not supported by vvdec");
        }
    }

    @Override
    protected DecoderReuseEvaluation canReuseDecoder(String name, Format oldF, Format newF) {
        // Re-init on format change (simplest + safest for benchmarking).
        return new DecoderReuseEvaluation(
                name, oldF, newF,
                DecoderReuseEvaluation.REUSE_RESULT_NO, /* discardReasons */ 0);
    }

    @Override
    public int supportsFormat(Format format) {
        final String mime = format.sampleMimeType;

        Log.i(TAG, "supportsFormat check for " + mime);

        // ExoPlayer 2.x doesn’t define a constant for VVC; accept common strings.
        final boolean isVvc = "video/vvc".equals(mime) || "video/h266".equals(mime);
        if (!isVvc) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
        if (format.drmInitData != null) {
            // SW path is clear-only
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // Drop only if >50 ms late (tune as needed for tests).
        return earlyUs < -50_000;
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
        // Seek forward to keyframe if we're ~0.5 s behind.
        return earlyUs < -500_000;
    }

    @Override
    protected boolean shouldForceRenderOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // Render when due or slightly late.
        return earlyUs <= 0;
    }

    @Override
    protected void onDisabled() {
        try {
            if (decoder != null) {
                decoder.setOutputSurface(null);
            }
            currentSurface = null;
        } finally {
            super.onDisabled();
        }
    }
}
