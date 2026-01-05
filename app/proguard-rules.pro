# NanoAi ProGuard Rules

# Keep native methods
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep LlamaBridge JNI class
-keep class com.nanoai.llm.LlamaBridge { *; }
-keep class com.nanoai.llm.LlamaBridge$* { *; }

# Keep model classes
-keep class com.nanoai.llm.model.** { *; }

# Jsoup (for web scraping)
-keeppackagenames org.jsoup.nodes

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.google.gson.** { *; }
-keep class * implements com.google.gson.TypeAdapterFactory
-keep class * implements com.google.gson.JsonSerializer
-keep class * implements com.google.gson.JsonDeserializer

# Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Keep data classes for serialization
-keep class com.nanoai.llm.rag.** { *; }
-keep class com.nanoai.llm.vector.** { *; }
