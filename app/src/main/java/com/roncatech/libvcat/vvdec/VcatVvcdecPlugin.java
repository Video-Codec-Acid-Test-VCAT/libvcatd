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

import android.content.Context;
import android.os.Handler;

import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.decoder.DecoderException;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.video.VideoRendererEventListener;

import com.roncatech.libvcat.decoder.NonStdDecoderStsdParser;
import com.roncatech.libvcat.decoder.VcatDecoderPlugin;
import com.roncatech.libvcat.decoder.VideoConfiguration;

@UnstableApi
public class VcatVvcdecPlugin implements VcatDecoderPlugin, NonStdDecoderStsdParser {

    public static final String mimeType = "video/vvc";

    @Override
    public int sampleEntry4ccCode() {
        return Util.getIntegerCodeForString("vvc1");
    }

    @Override
    public int codecConfiguration4ccCode() {
        return Util.getIntegerCodeForString("vvcC");
    }

    @Override
    public String mimeType() {
        return mimeType;
    }

    @Override
    public VideoConfiguration parseStsd(byte[] data) {
        return VvcVideoCfgParser.parseStsd(data);
    }

    @Override
    public String getId() {
        return "vcat.vvdec";
    }

    @Override
    public String getDisplayName() {
        return getId() + "-" + getVersion();
    }

    @Override
    public String getVersion() {
        // Bump as you ship new builds
        return NativeVvdec.vvdecGetVersion();
    }

    @Override
    public String getMimeType() {
        return mimeType;
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
        VvdecProvider vvdec = new VvdecProvider(threads);
        if (vvdec.isAvailable(context)) {
            return vvdec.build(allowedJoiningTimeMs, eventHandler, eventListener);
        }
        throw new DecoderException("Unable to build 'vcat-vvdec' renderer");
    }
}
