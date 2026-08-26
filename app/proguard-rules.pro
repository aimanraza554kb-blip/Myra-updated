# MYRA ProGuard rules
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keep class com.myra.assistant.data.model.** { *; }
-keep class com.myra.assistant.gemini.** { *; }

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Room
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
