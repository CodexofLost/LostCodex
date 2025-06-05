# ----------------------------------------
# ProGuard rules for FindMyDevice app
# ----------------------------------------

# Keep the main application class
-keep class com.save.me.** { *; }

# --- AndroidX and Jetpack (safe defaults) ---
-keep class androidx.** { *; }
-dontwarn androidx.**

# --- Keep classes for Gson/Retrofit/OkHttp (for JSON serialization/deserialization) ---
-keep class com.google.gson.** { *; }
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**

# --- Room (Database ORM) ---
-keep class androidx.room.** { *; }
-keep @androidx.room.* class * { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-keepclassmembers class * {
    @androidx.room.* <methods>;
    @androidx.room.* <fields>;
}

# --- Parcelable/Parcelize (Kotlin) ---
-keep class kotlinx.parcelize.** { *; }
-keepclassmembers class * implements android.os.Parcelable {
    static ** CREATOR;
}

# --- Keep for Kotlin Reflection, Coroutines, and Compose ---
-keep class kotlin.** { *; }
-dontwarn kotlin.**
-keepclassmembers class kotlinx.coroutines.** { *; }
-dontwarn kotlinx.coroutines.**
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- Keep for LeakCanary (for debug builds, safe to ignore in release) ---
-dontwarn com.squareup.leakcanary.**

# --- Firebase and Google Play Services ---
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# --- Telegram Bot API (Pengrad) ---
-keep class com.pengrad.telegrambot.** { *; }
-dontwarn com.pengrad.telegrambot.**

# --- General keep rules for App Activities, Services, and BroadcastReceivers ---
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider

# --- Keep all annotations (sometimes needed for Room, Gson, etc.) ---
-keepattributes *Annotation*

# --- Keep for Gson - keep fields with @SerializedName ---
-keepclassmembers class * {
    @com.google.gson.annotations.SerializedName <fields>;
}

# --- For okio (used by OkHttp/Retrofit) ---
-dontwarn okio.**

# --- If using Coil (image loader) ---
-keep class coil.** { *; }
-dontwarn coil.**

# --- If using Jetpack Compose ---
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# --- Remove logging in release (optional, can reduce APK size) ---
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
}

# --- If using WorkManager ---
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**

# --- If using Paging ---
-keep class androidx.paging.** { *; }
-dontwarn androidx.paging.**

# --- If using Navigation Compose ---
-keep class androidx.navigation.** { *; }
-dontwarn androidx.navigation.**

# --- If using Security Crypto ---
-keep class androidx.security.** { *; }
-dontwarn androidx.security.**

# --- General safe fallback (optional, for troubleshooting) ---
# -dontoptimize
# -dontpreverify

# --- End of ProGuard rules ---