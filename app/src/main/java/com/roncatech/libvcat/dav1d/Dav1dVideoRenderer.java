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

package com.roncatech.libvcat.dav1d;

import android.os.Handler;
import android.util.Log;
import android.view.Surface;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.decoder.CryptoConfig; // ExoPlayer 2.x package
import com.google.android.exoplayer2.decoder.Decoder;
import com.google.android.exoplayer2.decoder.DecoderException;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderReuseEvaluation;
import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.video.DecoderVideoRenderer;
import com.google.android.exoplayer2.video.VideoRendererEventListener;

public final class Dav1dVideoRenderer extends DecoderVideoRenderer {

    private final static String TAG = "Dav1dVideoRenderer";

    private static final int MAX_DROPPED_FRAMES_TO_NOTIFY = 50;

    private final int frameThreads;
    private final int tileThreads;

    private Dav1dDecoder decoder;
    private Surface currentSurface;

    public Dav1dVideoRenderer(
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int frameThreads,
            int tileThreads) {
        // NOTE: 4-arg super() is required in ExoPlayer 2.x
        super(allowedJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_FRAMES_TO_NOTIFY);
        this.frameThreads = Math.max(1, frameThreads);
        this.tileThreads  = Math.max(1, tileThreads);
    }

    @Override public String getName() { return "Dav1dVideoRenderer"; }


    @Override
    protected Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
    createDecoder(Format format, CryptoConfig cryptoConfig) throws Dav1dDecoderException {
        this.decoder = new Dav1dDecoder(
                frameThreads,
                tileThreads);

        if(this.currentSurface != null){
            this.decoder.setOutputSurface(this.currentSurface);
        }
        return this.decoder;
    }

    @Override
    protected void renderOutputBufferToSurface(VideoDecoderOutputBuffer outputBuffer, Surface surface)
            throws Dav1dDecoderException {
        if (decoder == null) {
            throw new Dav1dDecoderException(
                    "Failed to render output buffer to surface: decoder is not initialized.");
        }
        // Only notify native when the Surface actually changes.
        if (surface != currentSurface) {
            currentSurface = surface;                  // may be null
            decoder.setOutputSurface(currentSurface);  // JNI caches/release ANativeWindow
        }

        decoder.renderToSurface((Dav1dOutputBuffer)outputBuffer);
        outputBuffer.release();
    }

    private static String videoOutputModeStr(int outputMode){
        switch (outputMode){
            case C.VIDEO_OUTPUT_MODE_YUV:
                return "VIDEO_OUTPUT_MODE_YUV";
            case C.VIDEO_OUTPUT_MODE_SURFACE_YUV:
                return "VIDEO_OUTPUT_MODE_SURFACE_YUV";
        }

        return "VIDEO_OUTPUT_MODE_NONE";
    }
    @Override
    protected void setDecoderOutputMode(int outputMode) {

        Log.d(TAG,"setDecoderOutputMode="+outputMode);

        switch (outputMode) {
            case com.google.android.exoplayer2.C.VIDEO_OUTPUT_MODE_YUV:
            case com.google.android.exoplayer2.C.VIDEO_OUTPUT_MODE_SURFACE_YUV:
            case C.VIDEO_OUTPUT_MODE_NONE:
                // dav1d always outputs YUV planes. If your Dav1dDecoder exposes setOutputMode(),
                // set it to YUV; otherwise you can ignore this call.
                // example: decoder.setOutputMode(com.google.android.exoplayer2.C.VIDEO_OUTPUT_MODE_YUV);
                return;

            default:
                throw new IllegalArgumentException(
                        "Surface output mode (" + videoOutputModeStr(outputMode) + ") not supported by dav1d");
        }
    }

    @Override
    protected DecoderReuseEvaluation canReuseDecoder(String name, Format oldF, Format newF) {
        // Re-init on format change.
        return new DecoderReuseEvaluation(
                name,
                oldF,
                newF,
                DecoderReuseEvaluation.REUSE_RESULT_NO, // or just 0 if the constant isn't present
                0                                       // discardReasons bitmask
        );
    }

    @Override
    public int supportsFormat(Format format) {
        final String mime = format.sampleMimeType;
        if (!MimeTypes.VIDEO_AV1.equals(mime)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
        // dav1d path is clear-only (no DRM)
        if (format.drmInitData != null) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // only drop if >50 ms late
        return earlyUs < -50_000;
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
        // only skip forward to a keyframe if we're ~0.5 s behind
        return earlyUs < -500_000;
    }

    @Override
    protected boolean shouldForceRenderOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // render when due or slightly late
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
