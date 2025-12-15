package com.roncatech.libvcat.decoder;

import androidx.media3.common.util.UnstableApi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Central registry for VCAT decoder plugins (last-wins). */
@UnstableApi
public final class VcatDecoderManager {

    private static final VcatDecoderManager INSTANCE = new VcatDecoderManager();
    private final ConcurrentMap<String, VcatDecoderPlugin> decoders = new ConcurrentHashMap<>();

    private VcatDecoderManager() {
        InternalDecoderLoader.loadOnce(this);
    }

    public static VcatDecoderManager getInstance() {
        return INSTANCE;
    }

    /** Register/replace a decoder by its ID (last registration wins). */
    public boolean registerDecoder(VcatDecoderPlugin decoder) {
        Objects.requireNonNull(decoder, "decoder");
        String id = Objects.requireNonNull(decoder.getId(), "decoder.getId()");

        if(!this.decoders.containsKey(id)) {
            decoders.put(id, decoder); // first wins
            return true;
        }

        return false;
    }

    public VcatDecoderPlugin getDecoder(String id){
        return this.decoders.getOrDefault(id, null);
    }

    /** Snapshot list of all registered decoders. */
    public List<VcatDecoderPlugin> getDecoders() {
        return Collections.unmodifiableList(new ArrayList<>(decoders.values()));
    }

    /** Snapshot list of all registered decoders for specified mime type. */
    public List<VcatDecoderPlugin> getDecodersForMimeType(String mimeType) {
        List<VcatDecoderPlugin> decodersForMimeType = new ArrayList<>();

        for(VcatDecoderPlugin curDecoder : decoders.values()){
            if(curDecoder.getMimeType().equals(mimeType)){
                decodersForMimeType.add(curDecoder);
            }
        }
        return Collections.unmodifiableList(decodersForMimeType);
    }

    public List<VcatDecoderPlugin> getAllDecoderse() {
        return Collections.unmodifiableList(new ArrayList<>(decoders.values()));
    }

    public Map<Integer, NonStdDecoderStsdParser> getNonStandardDecoders(){
        Map<Integer, NonStdDecoderStsdParser> nonStandardDecoders = new HashMap();

        for(VcatDecoderPlugin curDecoder : decoders.values()){
            if(curDecoder instanceof NonStdDecoderStsdParser){
                NonStdDecoderStsdParser nonStd = (NonStdDecoderStsdParser) curDecoder;
                nonStandardDecoders.put(nonStd.sampleEntry4ccCode(), nonStd);
            }
        }
        return Collections.unmodifiableMap(nonStandardDecoders);
    }
}
