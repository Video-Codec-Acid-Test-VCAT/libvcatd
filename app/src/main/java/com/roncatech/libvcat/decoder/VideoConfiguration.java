package com.roncatech.libvcat.decoder;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.util.MimeTypes;

import java.util.ArrayList;
import java.util.List;

public class VideoConfiguration {
    public final String mimeType;
    public final int nalUnitLengthFieldLength;
    public final int width;
    public final int height;
    public final @Nullable String codecs;
    public final @C.ColorSpace int colorSpace;
    public final @C.ColorRange int colorRange;
    public final @C.ColorTransfer int colorTransfer;
    public final float pixelWidthHeightRatio;
    public final List<byte[]> initializationData;

    private VideoConfiguration(
            String mimeType,
            int nalUnitLengthFieldLength,
            int width,
            int height,
            int colorSpace,
            int colorRange,
            int colorTransfer,
            float pixelWidthHeightRatio,
            String codecs,
            List<byte[]> initializationData){
        this.mimeType = mimeType;
        this.nalUnitLengthFieldLength = nalUnitLengthFieldLength;
        this.width = width;
        this.height = height;
        this.colorSpace = colorSpace;
        this.colorRange = colorRange;
        this.colorTransfer = colorTransfer;
        this.pixelWidthHeightRatio = pixelWidthHeightRatio;
        this.codecs = codecs;
        this.initializationData = new ArrayList<>(initializationData);
    }

    public static class Builder{
        public String mimeType = MimeTypes.VIDEO_UNKNOWN;
        public int nalUnitLengthFieldLength = C.LENGTH_UNSET;
        public int width = Format.NO_VALUE;
        public int height = Format.NO_VALUE;
        public @Nullable String codecs = "";
        public @C.ColorSpace int colorSpace = Format.NO_VALUE;
        public @C.ColorRange int colorRange = Format.NO_VALUE;
        public @C.ColorTransfer int colorTransfer = Format.NO_VALUE;
        public float pixelWidthHeightRatio = 1;
        public List<byte[]> initializationData = new ArrayList<>();

        public VideoConfiguration build(){
            return new VideoConfiguration(mimeType,
                    nalUnitLengthFieldLength,
                    width, height,
                    colorSpace,
                    colorRange,
                    colorTransfer,
                    pixelWidthHeightRatio,
                    codecs,
                    initializationData);
        }
    }
}
