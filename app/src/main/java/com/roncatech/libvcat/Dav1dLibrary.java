package com.roncatech.libvcat;

/** Loads the JNI shim once. */
public final class Dav1dLibrary {
    private static volatile boolean loaded;
    private Dav1dLibrary() {}
    public static synchronized void load() {
        if (!loaded) {
            System.loadLibrary("vcat_jni");
            loaded = true;
        }
    }
}
