# Keep GreatFire FreeBrowser Kotlin classes & WebView JavascriptInterface bridges
-keep class org.greatfire.** { *; }
-keepattributes JavascriptInterface,*Annotation*
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# OkHttp & Conscrypt warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
