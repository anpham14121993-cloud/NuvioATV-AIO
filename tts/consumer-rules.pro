# VieNeu offline TTS: the runtime JNI layer resolves these classes and members
# by name, so they must survive R8 untouched.
-keep class com.nuvio.app.vieneu.** { *; }
-keepclasseswithmembernames class com.nuvio.app.vieneu.* {
    native <methods>;
}

# ONNX Runtime: libonnxruntime4j_jni.so looks up ai.onnxruntime classes and
# members by their original names. The AAR only keeps part of the public API,
# so R8 renamed internals (e.g. ai.onnxruntime.a) in the release APK and the
# native side aborted the process with SIGABRT a second into createSession -
# "hb 1s load" then no trace. NuvioMobile-AIO hit and fixed the same release
# crash (1f8ab976) by keeping the whole package.
-keep class ai.onnxruntime.** { *; }
-keep interface ai.onnxruntime.** { *; }
-keep enum ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**
