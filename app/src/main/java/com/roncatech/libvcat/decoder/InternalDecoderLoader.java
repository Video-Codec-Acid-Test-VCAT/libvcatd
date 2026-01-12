package com.roncatech.libvcat.decoder;

import com.roncatech.libvcat.dav1d.VcatDav1dPlugin;
import com.roncatech.libvcat.vvdec.VcatVvcdecPlugin;
import com.roncatech.libvcat.vvdec.VvdecProvider;
// import com.roncatech.libvcat.vvc.VcatVvdecPlugin; // example: add more here

import java.util.concurrent.atomic.AtomicBoolean;

/** Registers libvcat's built-in decoders (opaque to callers). */
final class InternalDecoderLoader {
    private static final AtomicBoolean didLoad = new AtomicBoolean(false);

    private InternalDecoderLoader() {}

    static void loadOnce(VcatDecoderManager manager) {
        if (!didLoad.compareAndSet(false, true)) {
            return;
        }

        // Register all *internal* decoders here:
        manager.registerDecoder(new VcatDav1dPlugin());
        manager.registerDecoder(new VcatVvcdecPlugin());
    }
}
