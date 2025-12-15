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

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

@UnstableApi
final class Dav1dAv1Provider implements Dav1dAv1RendererProvider {
    private final int frameThreads, tileThreads;

    public Dav1dAv1Provider(int frameThreads, int tileThreads) {
        this.frameThreads = Math.max(1, frameThreads);
        this.tileThreads  = Math.max(1, tileThreads);
    }

    @Override
    public String id() {
        return "dav1d";
    }

    @Override
    public boolean isAvailable(Context context) {
        try {
            Dav1dLibrary.load(); // will throw UnsatisfiedLinkError if lib missing
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public Renderer build(long joinMs, Handler h, VideoRendererEventListener l) {
        // DRM hard-fail lives in Dav1dVideoRenderer#createDecoder()
        return new Dav1dVideoRenderer(joinMs, h, l, frameThreads, tileThreads);
    }
}
