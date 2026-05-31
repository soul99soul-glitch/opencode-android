# OpenCode Android ProGuard Rules

# Keep serialization models
-keepclassmembers class com.opencode.android.data.model.** {
    <fields>;
}

# Keep Ktor
-dontwarn io.ktor.**
-keep class io.ktor.** { *; }

# Keep kotlinx.serialization
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.opencode.android.data.model.**$$serializer { *; }
-keepclassmembers class com.opencode.android.data.model.** {
    *** Companion;
}
-keepclasseswithmembers class com.opencode.android.data.model.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
