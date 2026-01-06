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

import android.view.Surface;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.decoder.DecoderException;
import androidx.media3.decoder.DecoderInputBuffer;
import androidx.media3.decoder.DecoderOutputBuffer;
import androidx.media3.decoder.SimpleDecoder;
import androidx.media3.decoder.VideoDecoderOutputBuffer;

import java.nio.ByteBuffer;

@UnstableApi
class VvdecDecoder
        extends SimpleDecoder<DecoderInputBuffer, VvdecOutputBuffer, DecoderException> {

    // Local copies of 2.x buffer flags to avoid Media3 suggestions.
    private static final int FLAG_END_OF_STREAM = 0x4;

    private static final int NUM_INPUT_BUFFERS  = 8;
    private static final int NUM_OUTPUT_BUFFERS = 4;

    private final int threads;

    private long nativeCtx; // 0 when released
    private Format inputFormat;

    @SuppressWarnings("unused")
    private boolean eosSignaled = false;

    VvdecDecoder(int threads) throws DecoderException {
        super(
                new DecoderInputBuffer[NUM_INPUT_BUFFERS],
                new VvdecOutputBuffer[NUM_OUTPUT_BUFFERS]);

        this.threads = Math.max(0, threads); // 0=single, -1=auto (native clamps)

        nativeCtx = NativeVvdec.nativeCreate(this.threads);
        if (nativeCtx == 0) {
            throw new DecoderException("nativeCreate failed");
        }
    }

    void setOutputSurface(@androidx.annotation.Nullable Surface surface) {
        if (nativeCtx == 0) return;
        NativeVvdec.nativeSetSurface(nativeCtx, surface);
    }

    @Override
    public String getName() {
        return "vcat-vvdec-" + NativeVvdec.vvdecGetVersion();
    }

    /** Called by the renderer on input format changes. */
    void setInputFormat(Format format) {
        this.inputFormat = format;
    }

    @Override
    protected VvdecOutputBuffer createOutputBuffer() {
        return new VvdecOutputBuffer(
                new VideoDecoderOutputBuffer.Owner() {
                    @Override
                    public void releaseOutputBuffer(DecoderOutputBuffer buffer) {
                        VvdecDecoder.this.releaseOutputBuffer((VvdecOutputBuffer) buffer);
                    }
                });
    }

    @Override
    protected DecoderInputBuffer createInputBuffer() {
        return new DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT);
    }

    @Override
    protected DecoderException createUnexpectedDecodeException(Throwable error) {
        return new DecoderException("Unexpected decode error", error);
    }

    @Override
    protected void releaseOutputBuffer(VvdecOutputBuffer out) {
        if (out.nativePic != 0 && nativeCtx != 0) {
            NativeVvdec.nativeReleasePicture(nativeCtx, out.nativePic);
            out.nativePic = 0;
        }
        super.releaseOutputBuffer(out);
    }

    @Override
    public void release() {
        if (nativeCtx != 0) {
            NativeVvdec.nativeSetSurface(nativeCtx, null);
        }
        super.release();
        if (nativeCtx != 0) {
            NativeVvdec.nativeClose(nativeCtx);
            nativeCtx = 0;
        }
    }

    @Override
    protected DecoderException decode(
            DecoderInputBuffer in, VvdecOutputBuffer out, boolean reset) {

        if (nativeCtx == 0) {
            return new DecoderException("Decoder released");
        }

        if (reset) {
            NativeVvdec.nativeFlush(nativeCtx);
            eosSignaled = false;
        }

        // EOS: signal EOF to decoder and try to drain one last frame.
        if (in.isEndOfStream()) {
            NativeVvdec.nativeSignalEof(nativeCtx);
            eosSignaled = true;

            int[]  wh  = new int[2];
            long[] pts = new long[1];

            long h = NativeVvdec.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) {
                return new DecoderException("vvdec dequeue failed: " + wh[1]);
            }
            if (h != 0) {
                out.mode   = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
                out.timeUs = pts[0];
                out.width  = wh[0];
                out.height = wh[1];
                out.format = inputFormat;
                out.nativePic = h;
                // Caller will see a normal frame followed by a buffer with EOS flag later.
                return null;
            }
            out.addFlag(FLAG_END_OF_STREAM);
            return null;
        }

        if (in.data == null) {
            return new DecoderException("Input buffer has no data");
        }

        // Gav1-style decode flow:
        //  - Push the entire access unit into the decoder.
        //  - Decide decodeOnly based on outputStartTime.
        //  - Always attempt to dequeue a frame (whether decodeOnly or not).
        final ByteBuffer data = in.data;
        final int offset      = data.position();
        final int size        = data.remaining();

        int rc = NativeVvdec.nativeQueueInput(
                nativeCtx,
                data,
                offset,
                size,
                in.timeUs);

        // Treat 0 as success. If native uses -11/EAGAIN, let that just mean "no new frame yet".
        if (rc != 0 && rc != -11) {
            return new DecoderException("nativeQueueInput failed: " + rc);
        }

        boolean decodeOnly = !isAtLeastOutputStartTimeUs(in.timeUs);

        int[]  wh  = new int[2];
        long[] pts = new long[1];
        long h = NativeVvdec.nativeDequeueFrame(nativeCtx, wh, pts);

        if (wh[0] == -1) {
            return new DecoderException("vvdec dequeue failed: " + wh[1]);
        }

        if (h != 0) {
            out.mode   = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
            out.timeUs = pts[0];
            out.width  = wh[0];
            out.height = wh[1];
            out.format = inputFormat;
            out.nativePic = h;

            // Align with gav1: preroll frames are still dequeued but flagged to be skipped.
            if (decodeOnly) {
                out.shouldBeSkipped = true;
            }
        }

        // Returning null tells SimpleDecoder "no fatal error".
        // If no frame was produced (h == 0), the outputBuffer will be ignored by the caller.
        return null;
    }

    /** Called by the renderer to blit the decoded frame to a Surface. */
    void renderToSurface(VvdecOutputBuffer out) throws DecoderException {
        if (nativeCtx == 0 || out.nativePic == 0) {
            return;
        }
        int rc = NativeVvdec.nativeRenderToSurface(nativeCtx, out.nativePic);
        if (rc < 0) {
            throw new DecoderException("nativeRenderToSurface failed: " + rc);
        }
    }
}
