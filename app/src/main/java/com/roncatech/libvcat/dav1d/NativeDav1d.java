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

import android.view.Surface;
import java.nio.ByteBuffer;

final class NativeDav1d {
    static {
        // If libdav1d_jni has DT_NEEDED on libdav1d, loading the JNI lib is enough.
        // Loading dav1d first is harmless; ignore if it isn't needed.
        try { System.loadLibrary("dav1d"); } catch (Throwable ignored) {}
        System.loadLibrary("vcat_jni");
    }

    // Creates a decoder context; returns 0 on failure.
    public static native long nativeCreate(int frameThreads, int tileThreads);

    // Flushes decoder state (drains/clears internal queues).
    public static native void nativeFlush(long ctx);

    // Destroys the decoder context and frees resources.
    public static native void nativeClose(long ctx);

    static native boolean nativeHasCapacity(long ctx);
    static native void    nativeSignalEof(long ctx);

    // Queues one compressed sample (direct ByteBuffer required).
    // Returns 0 on success; negative errno on error (e.g. -22 for EINVAL).
    public static native int nativeQueueInput(
            long ctx, ByteBuffer buffer, int offset, int size, long ptsUs);

    // Attempts to dequeue a decoded frame.
    // Returns 0 if no frame yet; otherwise a non-zero native handle.
    // outWidthHeight[0]=w, [1]=h; outPtsUs[0]=pts.
    public static native long nativeDequeueFrame(
            long ctx, int[] outWidthHeight, long[] outPtsUs);

    // Renders a decoded frame to the given Surface (RGBA8888 blit).
    // Returns 0 on success; negative on error.
    public static native int nativeRenderToSurface(
            long ctx, long nativePic);

    // Releases a previously dequeued native picture handle.
    public static native void nativeReleasePicture(long ctx, long nativePic);

    // Get the dav1d decoder version.
    public static native String dav1dGetVersion();

    public static native void nativeSetSurface(long handle, Surface surface);

    private NativeDav1d() {}
}
