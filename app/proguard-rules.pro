# Keep Gson DTO field names intact for reflective (de)serialization.
-keepclassmembers class com.tvmedia.openlist.data.remote.dto.** { <fields>; }
-keep class com.tvmedia.openlist.data.remote.dto.** { *; }

# Retrofit / OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
