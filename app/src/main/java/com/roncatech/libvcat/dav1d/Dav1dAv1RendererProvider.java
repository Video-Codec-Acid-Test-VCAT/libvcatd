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
interface Dav1dAv1RendererProvider {
   /** Stable id used by user config, e.g. "dav1d" or "mediacodec". */
   String id();

   /** Quick availability probe (no heavy work). Return false if unavailable on this device/build. */
   boolean isAvailable(Context context);

   /** Build the renderer or throw IllegalStateException for strict fail. */
   Renderer build(long allowedJoiningTimeMs,
                  Handler eventHandler,
                  VideoRendererEventListener eventListener);
}
