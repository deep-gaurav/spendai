# Phase 1 proguard rules. Keep the surface minimal until we have a
# release build actually configured.

# Keep all LiteRT-LM native entry points; the JNI bridge relies on
# specific class/method names being preserved.
-keep class com.google.ai.edge.litertlm.** { *; }
-keepclassmembers class com.google.ai.edge.litertlm.** { *; }

# Room: keep generated DAO implementations.
-keep class * extends androidx.room.RoomDatabase
-keep class androidx.room.RoomDatabase { *; }
-keepclassmembers class * extends androidx.room.RoomDatabase {
    public static <fields>;
}

# Kotlin metadata for reflection-using libs.
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keepattributes *Annotation*
