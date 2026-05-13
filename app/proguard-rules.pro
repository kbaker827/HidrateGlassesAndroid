# Add project specific ProGuard rules here.

# Keep Retrofit service interfaces
-keep interface com.hidrateglasses.data.api.** { *; }

# Keep Gson serialization models
-keep class com.hidrateglasses.data.models.** { *; }
-keep class com.hidrateglasses.data.api.**Response { *; }
-keep class com.hidrateglasses.data.api.**Request { *; }

# OkHttp / Retrofit
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepattributes Signature
-keepattributes Exceptions

# Hilt
-keep class dagger.hilt.** { *; }
-keep @dagger.hilt.android.lifecycle.HiltViewModel class * { *; }
-keepclassmembers class * {
    @javax.inject.Inject <fields>;
    @javax.inject.Inject <methods>;
}

# Coroutines
-keep class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**

# DataStore
-keep class androidx.datastore.** { *; }

# Rokid CXR SDK
-keep class com.rokid.** { *; }
-dontwarn com.rokid.**

# Compose
-keep class androidx.compose.** { *; }
