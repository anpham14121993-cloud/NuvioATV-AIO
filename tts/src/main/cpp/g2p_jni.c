/* JNI bridge between com.nuvio.app.vieneu.G2P and the sea-g2p C ABI.
 *
 * The Rust library (libsea_g2p_rs.so) is built from
 * https://github.com/pnnbao97/sea-g2p with the "capi" feature; this file only
 * forwards the three JNI entry points declared in G2P.java.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "sea_g2p.h"

JNIEXPORT jlong JNICALL
Java_com_nuvio_app_vieneu_G2P_nativeOpen(JNIEnv *env, jclass clazz, jstring dict_path) {
    (void) clazz;
    if (dict_path == NULL) {
        return 0;
    }
    const char *path = (*env)->GetStringUTFChars(env, dict_path, NULL);
    if (path == NULL) {
        return 0;
    }
    sea_g2p *handle = sea_g2p_open(path);
    (*env)->ReleaseStringUTFChars(env, dict_path, path);
    return (jlong) (intptr_t) handle;
}

JNIEXPORT jstring JNICALL
Java_com_nuvio_app_vieneu_G2P_nativePhonemize(JNIEnv *env, jclass clazz, jlong handle,
                                              jstring text, jboolean punc_norm) {
    (void) clazz;
    if (handle == 0 || text == NULL) {
        return NULL;
    }
    const char *utf8 = (*env)->GetStringUTFChars(env, text, NULL);
    if (utf8 == NULL) {
        return NULL;
    }
    char *out = sea_g2p_phonemize((const sea_g2p *) (intptr_t) handle, utf8, punc_norm ? 1 : 0);
    (*env)->ReleaseStringUTFChars(env, text, utf8);
    if (out == NULL) {
        return NULL;
    }
    jstring result = (*env)->NewStringUTF(env, out);
    sea_g2p_string_free(out);
    return result;
}

JNIEXPORT void JNICALL
Java_com_nuvio_app_vieneu_G2P_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env;
    (void) clazz;
    if (handle == 0) {
        return;
    }
    sea_g2p_close((sea_g2p *) (intptr_t) handle);
}
