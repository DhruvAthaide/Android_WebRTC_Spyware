-keepclassmembers class * extends android.app.Activity {
    public void *(android.view.View);
}
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**

# ── WebRTC ─────────────────────────────────────────────────────
-keep class org.webrtc.** { *; }
-dontwarn org.webrtc.**
-keepclassmembers class org.webrtc.** { *; }

# ── Socket.IO & Engine.IO ──────────────────────────────────────
-keep class io.socket.** { *; }
-dontwarn io.socket.**
-keep class io.engineio.** { *; }
-dontwarn io.engineio.**

# ── OkHttp ─────────────────────────────────────────────────────
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-keep class okio.** { *; }
-dontwarn okio.**
-dontwarn javax.annotation.**

# ── Firebase ───────────────────────────────────────────────────
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ── JSON / org.json ────────────────────────────────────────────
-keep class org.json.** { *; }

# ── App classes (prevent stripping of inner classes) ───────────
-keep class com.example.wallpaperapplication.** { *; }
-keepclassmembers class com.example.wallpaperapplication.** { *; }

# ── WorkManager ────────────────────────────────────────────────
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**