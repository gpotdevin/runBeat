# Android ProGuard rules for RunBeat
# Add project specific ProGuard rules here.
# By default, the Android Gradle plugin adds ProGuard rules for:
#   - All classes in android.jar
#   - All activities, services, receivers, and content providers
#
# For ExoPlayer
-keep class com.google.android.exoplayer2.** { *; }
-dontwarn com.google.android.exoplayer2.**

# For Hilt
-keep class com.bpmapp.audio.**_* { *; }
-keep class com.bpmapp.audio.**_Factory { *; }
-keep class com.bpmapp.audio.**_MembersInjector { *; }

# For Room
-keep class androidx.room.** { *; }
-keep class * extends androidx.room.Database { *; }
-keep class * extends androidx.room.Entity { *; }
-keep class * extends androidx.room.Dao { *; }

# For Kotlin
-keep class kotlin.Metadata { *; }
-keep class kotlin.** { *; }
-keep class com.bpmapp.audio.** { *; }

# Keep all activities, services, and receivers
-keep class * extends android.app.Activity
-keep class * extends android.app.Service
-keep class * extends android.content.BroadcastReceiver

# Keep R classes
-keep class **.R$* { *; }

# Keep data binding classes
-keep class **.BR { *; }

# Keep view binding classes
-keep class **.databinding.** { *; }

# For Compose
-keep class androidx.compose.runtime.Composer { *; }
-keep class androidx.compose.runtime.ComposerImpl { *; }

# For TarsosDSP
-keep class be.tarsos.dsp.** { *; }
-dontwarn be.tarsos.dsp.**
