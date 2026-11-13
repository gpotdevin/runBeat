# Android ProGuard rules for RunBeat
# R8 runs on release builds (minifyEnabled true); only the rules required for
# runtime reflection, JNI bindings, and non-R8-aware libraries are kept here.

# Aubio BPM detector: native methods bound by name
# (Java_com_bpmapp_audio_audio_AubioBpmDetector_*)
-keep class com.bpmapp.audio.audio.AubioBpmDetector { *; }

# JAudiotagger: not R8-aware, used by MetadataEditor.kt
-keep class org.jaudiotagger.** { *; }
-dontwarn org.jaudiotagger.**

# Garmin Connect IQ SDK - keep all classes and their members.
# Required for proper unmarshalling of IQDevice and other SDK Parcelables
# sent by Garmin Connect Mobile via INCOMING_MESSAGE broadcasts.
# Without these rules R8 strips/obfuscates the SDK classes and the app
# crashes with BadParcelableException: ClassNotFoundException when
# unmarshalling: com.garmin.android.connectiq.IQDevice
-keep class com.garmin.android.connectiq.** { *; }
-dontwarn com.garmin.android.connectiq.**

# TrackSource enum persisted in Room as a string (Track.kt)
-keepclassmembers enum com.bpmapp.audio.data.TrackSource {
    public static **[] values();
    public static ** valueOf(java.lang.String);
    <fields>;
}

# Keep stack traces readable
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
