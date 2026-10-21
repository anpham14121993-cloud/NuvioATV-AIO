# Vieneu offline TTS: bundled int8 ONNX models are not user code.
-keep class com.nuvio.app.vieneu.** { *; }
-dontwarn ai.onnxruntime.**
