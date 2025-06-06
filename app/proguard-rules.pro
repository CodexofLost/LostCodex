# ----------------------------------------
# ProGuard rules for FindMyDevice app
# ----------------------------------------

# Keep all classes, methods, and fields inside your app package (prevents obfuscation/removal)
-keep class com.save.me.** { *; }

# --- AndroidX and Jetpack (safe defaults) ---
# Keep all AndroidX classes and ignore warnings
-keep class androidx.** { *; }
-dontwarn androidx.**

# --- Gson, Retrofit, OkHttp ---
# Keep everything related to Gson (used for JSON serialization/deserialization)
-keep class com.google.gson.** { *; }
# Keep everything for Retrofit (HTTP client)
-keep class retrofit2.** { *; }
# Keep everything for OkHttp (underlying HTTP library)
-keep class okhttp3.** { *; }
# Ignore warnings from Retrofit, OkHttp, and okio (dependency of OkHttp)
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Room Database ---
# Keep Room database classes
-keep class androidx.room.** { *; }
# Keep classes annotated with Room annotations (like @Entity, @Dao, etc.)
-keep @androidx.room.* class * { *; }
# Keep Room database classes extending RoomDatabase
-keep class * extends androidx.room.RoomDatabase { *; }
# Keep class members (methods/fields) annotated by Room
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# --- Kotlin Parcelable support ---
# Keep Kotlin Parcelize (serialization)
-keep class kotlinx.parcelize.** { *; }
# Keep members required for Parcelable implementations
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# --- Kotlin core, coroutines, and Jetpack Compose ---
# Keep all Kotlin classes
-keep class kotlin.** { *; }
-dontwarn kotlin.**
# Keep and ignore warnings for Kotlin coroutines
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
# Keep and ignore warnings for Jetpack Compose classes
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- LeakCanary (used in debug only, safe to suppress in release) ---
-dontwarn com.squareup.leakcanary.**

# --- Firebase and Google Play Services ---
# Keep and suppress warnings for Firebase-related classes
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
# Keep and suppress warnings for Google Play Services classes
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# --- Telegram Bot API (Pengrad) ---
# Keep and suppress warnings for Telegram Bot API client
-keep class com.pengrad.telegrambot.** { *; }
-dontwarn com.pengrad.telegrambot.**

# --- Core Android Components ---
# Keep all custom Activity, Service, BroadcastReceiver, and ContentProvider subclasses
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# --- Keep all annotations ---
# Needed for libraries like Room, Gson, Retrofit
-keepattributes *Annotation*

# --- Ensure Gson works with @SerializedName fields ---
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- okio (used under-the-hood by OkHttp/Retrofit) ---
-dontwarn okio.**

# --- Coil (Kotlin image loader) ---
# Keep and suppress warnings if using Coil
-keep class coil.** { *; }
-dontwarn coil.**

# --- Jetpack Compose support (UI toolkit) ---
# Keep and suppress warnings for Compose
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- Remove logs in release build (to reduce APK size and prevent log leaking) ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);  # Debug logs
    public static *** v(...);  # Verbose logs
    public static *** i(...);  # Info logs
    public static *** w(...);  # Warning logs
    public static *** e(...);  # Error logs
}

# --- WorkManager support ---
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# --- Paging library support ---
-keep class androidx.paging.** { *; }
-dontwarn androidx.paging.**

# --- Navigation Compose support ---
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# --- AndroidX Security (EncryptedSharedPreferences, etc.) ---
-keep class androidx.security.** { *; }
-dontwarn androidx.security.**

# --- Optional debugging switches (for troubleshooting builds) ---
# -dontoptimize      # Disable optimizations (only for debug)
# -dontpreverify     # Disable class verification (older Androids)

# --- End of ProGuard rules ---