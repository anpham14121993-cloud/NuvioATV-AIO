# VieNeu offline TTS: the runtime JNI layer resolves these classes and members
# by name, so they must survive R8 untouched.
-keep class com.nuvio.app.vieneu.** { *; }
-keepclasseswithmembernames class com.nuvio.app.vieneu.* {
    native <methods>;
}
-dontwarn ai.onnxruntime.**
