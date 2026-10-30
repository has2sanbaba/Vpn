# Keep Dr Browser Kotlin classes, JNI bridge & WebView JavascriptInterface bridges
-keep class org.greatfire.** { *; }
-keep class org.greatfire.browser.vpn.NativeProxyBridge {
    native <methods>;
    *;
}
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
