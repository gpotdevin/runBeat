# Android ProGuard rules for RunBeat
# R8 runs on release builds (minifyEnabled true); only the rules required for
# runtime reflection, JNI bindings, and non-R8-aware libraries are kept here.

# Aubio BPM detector: native methods bound by name
# (Java_com_bpmapp_audio_audio_AubioBpmDetector_*)
-keep class com.bpmapp.audio.audio.AubioBpmDetector { *; }

# JAudiotagger: not R8-aware, used by MetadataEditor.kt
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# TrackSource enum persisted in Room as a string (Track.kt)
-keepclassmembers enum com.bpmapp.audio.data.TrackSource {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Keep stack traces readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
