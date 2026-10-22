package com.nuvio.app.vieneu;

/** JNI wrapper over the sea-g2p C ABI (libsea_g2p_rs.so + libg2pjni.so). */
public final class G2P {
    static {
        System.loadLibrary("sea_g2p_rs");
        System.loadLibrary("g2pjni");
    }

    private static native long nativeOpen(String dictPath);
    private static native String nativePhonemize(long handle, String text, boolean puncNorm);
    private static native void nativeClose(long handle);

    private long handle;

    public G2P(String dictPath) {
        handle = nativeOpen(dictPath);
        if (handle == 0) throw new RuntimeException("sea_g2p_open failed for " + dictPath);
    }

    public String phonemize(String text) {
        return nativePhonemize(handle, text, true);
    }

    public void close() {
        if (handle != 0) { nativeClose(handle); handle = 0; }
    }
}
