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

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.decoder.DecoderOutputBuffer;
import com.google.android.exoplayer2.decoder.SimpleDecoder;
import com.google.android.exoplayer2.decoder.VideoDecoderOutputBuffer;

final class VvdecDecoder
        extends SimpleDecoder<DecoderInputBuffer, VvdecOutputBuffer, VvdecDecoderException> {

    // Local copies of 2.x buffer flags to avoid Media3 suggestions.
    private static final int FLAG_DECODE_ONLY   = 0x1;
    private static final int FLAG_END_OF_STREAM = 0x4;

    private static final int NUM_INPUT_BUFFERS  = 8;
    private static final int NUM_OUTPUT_BUFFERS = 4;

    private final int threads;

    private long nativeCtx; // 0 when released
    private Format inputFormat;
    private boolean eosSignaled = false;

    VvdecDecoder(int threads) throws VvdecDecoderException {
        super(
                new DecoderInputBuffer[NUM_INPUT_BUFFERS],
                new VvdecOutputBuffer[NUM_OUTPUT_BUFFERS]);

        this.threads = Math.max(0, threads); // 0=single, -1=auto (native clamps)

        nativeCtx = NativeVvdec.nativeCreate(this.threads);
        if (nativeCtx == 0) {
            throw new VvdecDecoderException("nativeCreate failed");
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
    protected VvdecDecoderException createUnexpectedDecodeException(Throwable error) {
        return new VvdecDecoderException("Unexpected decode error", error);
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
    protected VvdecDecoderException decode(DecoderInputBuffer in,
                                           VvdecOutputBuffer out,
                                           boolean reset) {
        if (nativeCtx == 0) return new VvdecDecoderException("Decoder released");
        if (reset) {
            NativeVvdec.nativeFlush(nativeCtx);
            eosSignaled = false;
        }
        final boolean decodeOnly = in.isDecodeOnly();

        // EOS path: signal EOF, try one drain; if none, set EOS flag.
        if (in.isEndOfStream()) {
            NativeVvdec.nativeSignalEof(nativeCtx);
            eosSignaled = true;

            int[] wh = new int[2];
            long[] pts = new long[1];
            long h = NativeVvdec.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) return new VvdecDecoderException("vvdec dequeue failed: " + wh[1]);

            if (h != 0) {
                out.mode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
                out.timeUs = pts[0];
                out.width = wh[0];
                out.height = wh[1];
                out.format = inputFormat;
                out.nativePic = h;
                if (decodeOnly) out.addFlag(FLAG_DECODE_ONLY);
                return null;
            }
            out.addFlag(FLAG_END_OF_STREAM);
            return null;
        }

        if (in.data == null) return new VvdecDecoderException("Input buffer has no data");

        // If the native queue is "full", try to drain first to make space.
        if (!NativeVvdec.nativeHasCapacity(nativeCtx)) {
            int[] wh = new int[2]; long[] pts = new long[1];
            long h = NativeVvdec.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) return new VvdecDecoderException("vvdec dequeue failed: " + wh[1]);
            if (h != 0) {
                out.mode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
                out.timeUs = pts[0];
                out.width = wh[0];
                out.height = wh[1];
                out.format = inputFormat;
                out.nativePic = h;
                if (decodeOnly) out.addFlag(FLAG_DECODE_ONLY);
                return null;
            }
            // No frame yet; let upstream feed again later.
            return null;
        }

        // Enqueue current input into native; vvdec may also return a frame immediately.
        int rc = NativeVvdec.nativeQueueInput(
                nativeCtx, in.data, in.data.position(), in.data.remaining(), in.timeUs);

        if (rc != 0) {
            // Our JNI returns 0 on success, negative errno otherwise.
            return new VvdecDecoderException("nativeQueueInput failed: " + rc);
        }

        // Non-blocking drain attempt (vvdec may have produced one).
        {
            int[] wh = new int[2]; long[] pts = new long[1];
            long h = NativeVvdec.nativeDequeueFrame(nativeCtx, wh, pts);
            if (wh[0] == -1) return new VvdecDecoderException("vvdec dequeue failed: " + wh[1]);
            if (h != 0) {
                out.mode = C.VIDEO_OUTPUT_MODE_SURFACE_YUV;
                out.timeUs = pts[0];
                out.width = wh[0];
                out.height = wh[1];
                out.format = inputFormat;
                out.nativePic = h;
                if (decodeOnly) out.addFlag(FLAG_DECODE_ONLY);
                return null;
            }
        }

        return null;
    }

    /** Called by the renderer to blit the decoded frame to a Surface. */
    void renderToSurface(VvdecOutputBuffer out) throws VvdecDecoderException {
        if (nativeCtx == 0 || out.nativePic == 0) return;
        int rc = NativeVvdec.nativeRenderToSurface(nativeCtx, out.nativePic);
        if (rc < 0) {
            throw new VvdecDecoderException("nativeRenderToSurface failed: " + rc);
        }
    }
}
