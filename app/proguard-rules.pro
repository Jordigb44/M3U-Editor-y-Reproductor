# Optimization flags
-repackageclasses
-allowaccessmodification

# Keep Data Models
-keep class com.example.data.** { *; }
-keepclassmembers class com.example.data.** { *; }

# Media3 / ExoPlayer
-keep class androidx.media3.** { *; }
-keepclassmembers class androidx.media3.** { *; }
-dontwarn androidx.media3.**

# OkHttp & Okio
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**

# Coil
-keep class coil.** { *; }
-keepclassmembers class coil.** { *; }
-dontwarn coil.**

# Kotlin Coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
