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

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.CryptoConfig;
import androidx.media3.decoder.Decoder;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.VideoDecoderOutputBuffer;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.RendererCapabilities;
import androidx.media3.exoplayer.video.DecoderVideoRenderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

@UnstableApi
final class Dav1dVideoRenderer extends DecoderVideoRenderer {

    private static final String TAG = "Dav1dVideoRenderer";
    private static final int MAX_DROPPED_FRAMES_TO_NOTIFY = 50;

    private final int frameThreads;
    private final int tileThreads;

    private Dav1dDecoder decoder;
    private Surface currentSurface;

    Dav1dVideoRenderer(
            long allowedJoiningTimeMs,
            Handler eventHandler,
            VideoRendererEventListener eventListener,
            int frameThreads,
            int tileThreads) {

        super(allowedJoiningTimeMs, eventHandler, eventListener, MAX_DROPPED_FRAMES_TO_NOTIFY);
        this.frameThreads = Math.max(1, frameThreads);
        this.tileThreads  = Math.max(1, tileThreads);
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public int supportsFormat(Format format) {
        final String mime = format.sampleMimeType;
        if (!MimeTypes.VIDEO_AV1.equalsIgnoreCase(mime)) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE);
        }
        // dav1d path is clear-only (no DRM).
        if (format.drmInitData != null) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM);
        }
        return RendererCapabilities.create(C.FORMAT_HANDLED);
    }

    @Override
    protected Decoder<DecoderInputBuffer, ? extends VideoDecoderOutputBuffer, ? extends DecoderException>
    createDecoder(Format format, CryptoConfig cryptoConfig) throws DecoderException {

        Dav1dDecoder decoder = new Dav1dDecoder(frameThreads, tileThreads);
        this.decoder = decoder;

        // If a Surface is already known (e.g., set before decoder creation), push it down now.
        if (currentSurface != null) {
            decoder.setOutputSurface(currentSurface);
        }

        // Let the decoder know the input format for width/height/format propagation.
        decoder.setInputFormat(format);

        return decoder;
    }

    @Override
    protected void renderOutputBufferToSurface(
            VideoDecoderOutputBuffer outputBuffer,
            Surface surface) throws DecoderException {

        if (decoder == null) {
            throw new DecoderException(
                    "Failed to render output buffer to surface: decoder is not initialized.");
        }

        // Only push surface to JNI when it actually changes; JNI caches/releases ANativeWindow.
        if (surface != currentSurface) {
            currentSurface = surface; // may be null
            decoder.setOutputSurface(currentSurface);
        }

        decoder.renderToSurface((Dav1dOutputBuffer) outputBuffer);
        outputBuffer.release();
    }

    private static String videoOutputModeStr(@C.VideoOutputMode int outputMode) {
        switch (outputMode) {
            case C.VIDEO_OUTPUT_MODE_YUV:
                return "VIDEO_OUTPUT_MODE_YUV";
            case C.VIDEO_OUTPUT_MODE_SURFACE_YUV:
                return "VIDEO_OUTPUT_MODE_SURFACE_YUV";
            case C.VIDEO_OUTPUT_MODE_NONE:
            default:
                return "VIDEO_OUTPUT_MODE_NONE";
        }
    }

    @Override
    protected void setDecoderOutputMode(@C.VideoOutputMode int outputMode) {
        // For now dav1d always outputs SURFACE_YUV from JNI.
        // This override exists mainly for logging and API parity.
        Log.d(TAG, "setDecoderOutputMode=" + outputMode
                + " (" + videoOutputModeStr(outputMode) + ")");

        switch (outputMode) {
            case C.VIDEO_OUTPUT_MODE_YUV:
            case C.VIDEO_OUTPUT_MODE_SURFACE_YUV:
            case C.VIDEO_OUTPUT_MODE_NONE:
                // No-op: Dav1dDecoder sets out.mode directly when it dequeues a frame.
                return;
            default:
                throw new IllegalArgumentException(
                        "Surface output mode (" + videoOutputModeStr(outputMode)
                                + ") not supported by dav1d");
        }
    }

    @Override
    protected DecoderReuseEvaluation canReuseDecoder(
            String decoderName, Format oldFormat, Format newFormat) {
        // Choice A: always recreate decoder on format change (safest).
        return new DecoderReuseEvaluation(
                decoderName,
                oldFormat,
                newFormat,
                DecoderReuseEvaluation.REUSE_RESULT_NO,
                /* discardReasons= */ 0);
    }

    @Override
    protected boolean shouldDropOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // Only drop if > 50 ms late. Less aggressive than some defaults; tuned for software decoders.
        return earlyUs < -150_000;
    }

    @Override
    protected boolean shouldDropBuffersToKeyframe(long earlyUs, long elapsedRealtimeUs) {
        // Only skip forward to a keyframe if we're ~0.5 s behind.
        return earlyUs < -1_000_000;
    }

    @Override
    protected boolean shouldForceRenderOutputBuffer(long earlyUs, long elapsedRealtimeUs) {
        // Render when due or slightly late.
        return earlyUs <= 20_000;
    }

    @Override
    protected void onDisabled() {
        try {
            if (decoder != null) {
                // Release cached ANativeWindow on the native side.
                decoder.setOutputSurface(null);
            }
            currentSurface = null;
        } finally {
            super.onDisabled();
        }
    }
}
