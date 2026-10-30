# Keep Dr Browser Kotlin classes, JNI bridge & WebView JavascriptInterface bridges
-keep class org.greatfire.** { *; }
-keep class org.greatfire.browser.vpn.NativeProxyBridge {
    native <methods>;
    *;
}
-keepclasseswithmembernames class * {
    native <methods>;
}
-keepattributes JavascriptInterface,*Annotation*,Signature,InnerClasses,EnclosingMethod
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep Lightning-Browser Activities, PreferenceFragments, Custom Views & Jsoup
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Application
-keep public class * extends androidx.preference.Preference
-keep public class * extends androidx.preference.PreferenceFragmentCompat
-keep public class org.jsoup.** {
    public *;
}

-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# RxJava3, OkHttp & AndroidX warnings
-dontwarn javax.annotation.**
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn androidx.window.extensions.**
-dontwarn androidx.window.sidecar.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
