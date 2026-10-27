# GreatFire Cronet & Envoy JNI rules
-keep class org.chromium.** { *; }
-keep class J.N { *; }
-keep class org.jni_zero.** { *; }
-dontwarn org.chromium.**
-dontwarn javax.annotation.**

# GreatFire IEnvoyProxy (gomobile Go bindings)
-keep class go.** { *; }
-keep class IEnvoyProxy.** { *; }
-dontwarn go.**
-dontwarn IEnvoyProxy.**

# GreatFire Browser classes
-keep class org.greatfire.** { *; }

# OkHttp & Conscrypt warnings
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
