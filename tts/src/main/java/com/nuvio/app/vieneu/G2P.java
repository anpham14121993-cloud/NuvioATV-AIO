package com.nuvio.app.vieneu;

/** JNI wrapper over the sea-g2p C ABI (libsea_g2p_rs.so + libg2pjni.so). */
public final class G2P {
    static {
        VieneuEngine.tr("g2p: load sea_g2p_rs");
        System.loadLibrary("sea_g2p_rs");
        VieneuEngine.tr("g2p: load g2pjni");
        System.loadLibrary("g2pjni");
        VieneuEngine.tr("g2p: libs ok");
    }

    private static native long nativeOpen(String dictPath);
    private static native String nativePhonemize(long handle, String text, boolean puncNorm);
    private static native void nativeClose(long handle);

    private long handle;

    public G2P(String dictPath) {
        VieneuEngine.tr("g2p: nativeOpen");
        handle = nativeOpen(dictPath);
        VieneuEngine.tr("g2p: nativeOpen ok handle=" + handle);
        if (handle == 0) throw new RuntimeException("sea_g2p_open failed for " + dictPath);
    }

    public String phonemize(String text) {
        return nativePhonemize(handle, text, true);
    }

    public void close() {
        if (handle != 0) { nativeClose(handle); handle = 0; }
    }
}
