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

import com.google.android.exoplayer2.util.ParsableBitArray;
import com.google.android.exoplayer2.util.ParsableByteArray;
import com.google.android.exoplayer2.util.Util;
import com.roncatech.libvcat.decoder.VideoConfiguration;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class VvcVideoCfgParser {

    private static final int SPS_NAL_UNIT_TYPE = 15; // VVC uses 33 for SPS (same as HEVC)

    private static String fourCcToString(int fourCc) {
        char[] chars = new char[4];
        chars[0] = (char) ((fourCc >> 24) & 0xFF);
        chars[1] = (char) ((fourCc >> 16) & 0xFF);
        chars[2] = (char) ((fourCc >> 8)  & 0xFF);
        chars[3] = (char) (fourCc & 0xFF);
        return new String(chars);
    }
    public static VideoConfiguration parseStsd(byte[] data){

        ParsableBitArray reader = new ParsableBitArray(data);

        // box
        int length = reader.readBits(32);
        String fourCC = fourCcToString(reader.readBits(32));

        // verify fourcc

        // full box
        int version = reader.readBits(8);
        int flags = reader.readBits(24);

        @SuppressWarnings("unused")
        int reserved = reader.readBits(5);

        int lengthSizeMinusOne = reader.readBits(2);
        boolean ptl_present_flag = reader.readBit();

        if (ptl_present_flag) {
            int ols_idx = reader.readBits(9);
            int num_sublayers = reader.readBits(3);
            int constant_frame_rate = reader.readBits(2);
            int chroma_format_idc = reader.readBits(2);
            int bit_depth_minus8 = reader.readBits(3);
            reserved = reader.readBits(5);

            // VvcPTLRecord
            reserved = reader.readBits(2);
            int num_bytes_constraint_info = reader.readBits(6);
            int general_profile_idc = reader.readBits(7);
            boolean general_tier_flag = reader.readBit();
            int general_level_idc = reader.readBits(8);
            boolean ptl_frame_only_constraint_flag = reader.readBit();
            boolean ptl_multilayer_enabled_flag = reader.readBit();
            if((8*num_bytes_constraint_info - 2) > 32){
                // throw error
            }
            int general_constraint_info = reader.readBits(8*num_bytes_constraint_info - 2);

            List<Boolean> ptl_sublayer_level_present_flag = new ArrayList<>();
            for (int i=num_sublayers - 2; i >= 0; i--) {
                ptl_sublayer_level_present_flag.add(reader.readBit());
            }

            List<Integer> ptl_reserved_zero_bit = new ArrayList<>();
            for (int j=num_sublayers; j<=8 && num_sublayers > 1; j++) {
                ptl_reserved_zero_bit.add(reader.readBits(1));
            }

            Map<Integer, Integer> sublayer_level_idc = new HashMap();
            for (int i=num_sublayers-2; i >= 0; i--) {
                if (ptl_sublayer_level_present_flag.get(i)) {
                    sublayer_level_idc.put(i, reader.readBits(8));
                }
            }

            int ptl_num_sub_profiles = reader.readBits(8);

            List<Integer> general_sub_profile_idc = new ArrayList<>();

            for (int j=0; j < ptl_num_sub_profiles; j++) {
                general_sub_profile_idc.add(reader.readBits(32));
            }

            // finished with VvcPTLRecord
            int max_picture_width = reader.readBits(16);
            int max_picture_height = reader.readBits(16);
            int avg_frame_rate = reader.readBits(16);
        }

        // Calculate the combined size of all VPS/SPS/PPS bitstreams.
        int numberOfArrays = reader.readBits(8);
        int csdLength = 0;
        int csdStartPosition = reader.getBytePosition();

        for (int i = 0; i < numberOfArrays; i++) {
            boolean array_completeness = reader.readBit();
            reserved = reader.readBits(2);
            int nAL_unit_type = reader.readBits(5);
            final int DCI_NUT = 13;
            final int OPI_NUT = 12;

            if(nAL_unit_type != DCI_NUT && nAL_unit_type != OPI_NUT){
                int num_nalus = reader.readBits(16);

                for (int j = 0; j < num_nalus; j++) {
                    int nal_unit_length = reader.readBits(16);
                    csdLength += 4 + nal_unit_length; // Start code and NAL unit.
                    reader.skipBytes(nal_unit_length);
                }
            }

        }

        VideoConfiguration.Builder builder = new VideoConfiguration.Builder();
        builder.mimeType = VcatVvcdecPlugin.mimeType;

        builder.nalUnitLengthFieldLength = lengthSizeMinusOne + 1;

        // Second pass: copy NALs into single buffer with start codes, and parse SPS if present
        reader.setPosition(csdStartPosition*8);
        byte[] buffer = new byte[csdLength];
        int bufferPosition = 0;

        for (int i = 0; i < numberOfArrays; i++) {
            @SuppressWarnings("unused")
            boolean array_completeness = reader.readBit();
            reserved = reader.readBits(2);

            int nAL_unit_type = reader.readBits(5);

            int num_nalus = reader.readBits(16);

            System.arraycopy(VvcNalUnitUtil.NAL_START_CODE, 0, buffer, bufferPosition, VvcNalUnitUtil.NAL_START_CODE.length);

            for (int j = 0; j < num_nalus; j++) {
                int readerPos = reader.getBytePosition();
                int remaining = data.length - readerPos;
                int nal_unit_length = reader.readBits(16);

                System.arraycopy(
                        VvcNalUnitUtil.NAL_START_CODE,
                        0,
                        buffer,
                        bufferPosition,
                        VvcNalUnitUtil.NAL_START_CODE.length);
                bufferPosition += VvcNalUnitUtil.NAL_START_CODE.length;
                reader.readBytes(buffer, bufferPosition, nal_unit_length);

                if (nAL_unit_type == SPS_NAL_UNIT_TYPE && j == 0) {
                    VvcNalUnitUtil.H266SpsData spsData =
                            VvcNalUnitUtil.parseH266SpsNalUnit(
                                    buffer, bufferPosition, bufferPosition + nal_unit_length);
                    builder.width = spsData.width;
                    builder.height = spsData.height;
//            colorSpace = spsData.colorSpace;
//            colorRange = spsData.colorRange;
//            colorTransfer = spsData.colorTransfer;
//            pixelWidthHeightRatio = spsData.pixelWidthHeightRatio;
//            codecs =
//                CodecSpecificDataUtil.buildHevcCodecString(
//                    spsData.generalProfileSpace,
//                    spsData.generalTierFlag,
//                    spsData.generalProfileIdc,
//                    spsData.generalProfileCompatibilityFlags,
//                    spsData.constraintBytes,
//                    spsData.generalLevelIdc);
                }
                bufferPosition += nAL_unit_type;

            }
        }

        builder.initializationData =
                csdLength == 0 ? Collections.emptyList() : Collections.singletonList(buffer);

        return builder.build();
    }
}
