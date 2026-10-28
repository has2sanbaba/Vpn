# 🛡️ مرورگر ضد فیلتر GreatFire VPN Browser (مبتنی بر `greatfire/envoy`)

مرورگر اندروید متن‌باز، امن و ضد سانسور ساخته‌شده بر پایه پروژه‌های رسمی سازمان **[GreatFire](https://github.com/greatfire)** (`envoy`، `IEnvoyProxy` و `freebrowser`) که **۱۰۰٪ ترافیک تمام وب‌سایت‌ها، ویدیوها، فرم‌ها، وب‌سوکت‌ها و حتی دانلود فایل‌ها** را از تونل‌های ضد فیلترینگ عبور می‌دهد.

---

## ✨ ویژگی‌های کلیدی

1. **موتور چهارگانه ضد فیلترینگ از خانواده GreatFire:**
   - **GreatFire `envoy` Patched Cronet + Geneva (`libcronet.108.0.5359.195.so`):** نسخه پچ‌شده موتور شبکه کرومیوم با دور زدن DPI سطح پکت (Geneva)، Encrypted Client Hello (ECH) و پروکسی‌های CDN Envoy.
   - **GreatFire `IEnvoyProxy` (`libgojni.so` - `e3.5.0`):** پشتیبانی بومی از پروتکل‌های **V2Ray (WS / HTTP / SRTP / WeChat)**، **XRay (XHTTP / VLESS)**، **Hysteria2 (QUIC)**، **Tor Snowflake (WebRTC)**، **Lyrebird (Obfs4 / Meek / WebTunnel)**، **MASQUE** و **Envoy ECH**.
   - **GreatFire `gfw-report/shadowsocks-rust` (`libsslocal.so`):** کلاینت سریع و مقاوم Shadowsocks با پشتیبانی از لینک‌های `ss://`.
   - **موتور بومی `gfproxy` (`libgfproxy.so` الهام‌گرفته از `greatfire/freebrowser`):** حل امن دامنه از طریق **DNS-over-HTTPS (DoH)** بدون نشت DNS به مخابرات + **تکه‌تکه‌سازی TLS ClientHello و SNI (Record Fragmentation + TCP Split)** بدون نیاز به سرور خارجی و با حفظ کامل گواهی اصلی SSL سایت‌ها!

2. **عبور ۱۰۰٪ ترافیک مرورگر از VPN + قفل امنیتی Kill-Switch:**
   - هدایت سطح موتور WebView (`AndroidX ProxyController`) به درگاه یکپارچه محلی GreatFire (`HTTP CONNECT` + `SOCKS5`) به‌همراه Fallback اختصاصی برای گوشی‌های قدیمی اندروید ۵ تا ۷.
   - **Kill-Switch واقعی:** در صورت قطع شدن تونل، هیچ بسته‌ای به شکل مستقیم ارسال نمی‌شود و آی‌پی کاربر هرگز لو نمی‌رود.
   - **سپر ضد نشت WebRTC** + **مسدودکننده تبلیغات و ردیاب‌ها** + **دانلود فایل مستقیم از داخل تونل VPN** + **دکمه پاک‌سازی اضطراری (Panic Wipe)** + **تب ناشناس (Incognito)**.

3. **قابل نصب روی تمام گوشی‌های اندروید (از قدیمی‌ترین تا جدیدترین) و تمام پردازنده‌ها:**
   - **نسخه‌های اندروید:** از **Android 5.0 Lollipop (`API 21`)** تا **Android 15 / 16 (`API 35+`)** همراه با:
     - امضای هم‌زمان **APK Signature Scheme V1 (JAR) + V2 + V3**
     - پشتیبانی از صفحات حافظه **16KB Page Size** برای اندروید ۱۵ و ۱۶
     - پل رمزنگاری داخلی گواهی‌های **Let's Encrypt (`ISRG Root X1 & X2`)** برای باز شدن بدون خطای سایت‌های HTTPS روی گوشی‌های قدیمی اندروید ۵، ۶ و ۷.۰!
     - `Core Library Desugaring` + `MultiDex`
   - **تمام معماری‌های پردازنده (CPU ABIs):**
     - `arm64-v8a` (گوشی‌های ۶۴ بیتی جدید)
     - `armeabi-v7a` (گوشی‌های ۳۲ بیتی قدیمی‌تر)
     - `x86_64` (تبلت‌ها، کروم‌بوک‌ها و شبیه‌سازهای ۶۴ بیتی)
     - `x86` (تبلت‌ها و شبیه‌سازهای ۳۲ بیتی اینتل)
     - `universal` (نسخه جامع شامل هر ۴ پردازنده در یک فایل APK)

4. **پشتیبانی از حالت بدون سرور + ورود لینک‌های دلخواه و اشتراک (Subscription):**
   - به‌محض نصب، حالت **Smart Auto (بدون نیاز به کانفیگ دستی)** فعال می‌شود.
   - امکان ایمپورت تک‌کلیکی از کلیپ‌بورد یا لینک اشتراک برای فرمت‌های:
     `vmess://`, `vless://`, `v2ws://`, `v2http://`, `v2srtp://`, `v2wechat://`, `ss://`, `hy2://`, `hysteria2://`, `envoy://`, `masque://`, `snowflake://`, `obfs4://`, `meek://`, `socks5://`, `http://` و لینک‌های اشتراک Base64 (`https://...`).

---

## 📥 دانلود فایل نصبی (APK) از GitHub Actions

در هر بار تغییر کد، ورک‌فلوی خودکار [`.github/workflows/build-android.yml`](.github/workflows/build-android.yml) تمام تست‌ها را اجرا کرده و فایل‌های APK امضاشده را می‌سازد:

1. وارد تب **Actions** در همین ریپوزیتوری گیت‌هاب شوید.
2. آخرین اجرای سبز **Build GreatFire VPN Browser (All Androids & All CPUs)** را باز کنید.
3. از بخش **Artifacts** بسته **`GreatFire-VPN-Browser-APKs`** را دانلود کنید:
   - `GreatFire-VPN-Browser-universal-release.apk` ← **پیشنهادی (قابل نصب روی تمام گوشی‌ها و پردازنده‌ها)**
   - `GreatFire-VPN-Browser-arm64-v8a-release.apk` ← سبک‌تر، مخصوص گوشی‌های ۶۴ بیتی
   - `GreatFire-VPN-Browser-armeabi-v7a-release.apk` ← سبک‌تر، مخصوص گوشی‌های قدیمی ۳۲ بیتی
   - `GreatFire-VPN-Browser-x86_64-release.apk` / `x86-release.apk` ← مخصوص تبلت‌های اینتل و شبیه‌سازها

---

## 🛠️ بیلد محلی (Local Build)

پیش‌نیازها: **JDK 17**، **Go 1.22+**، **Android SDK (Platform 35)** و **Android NDK (r26 / r27)**.

```bash
# ۱. دریافت کتابخانه‌های رسمی GreatFire (Envoy Cronet + IEnvoyProxy)
./scripts/fetch-greatfire-libs.sh

# ۲. کامپایل موتور بومی ضد فیلتر gfproxy برای هر ۴ معماری پردازنده
./scripts/build-gfproxy-android.sh

# ۳. ساخت کلید امضای V1+V2+V3 و بیلد نهایی APKها
./scripts/ensure-keystore.sh
./gradlew testDebugUnitTest assembleRelease
```

خروجی فایل‌های APK امضاشده در مسیر `app/build/outputs/apk/release/` قرار می‌گیرد.

---

## 🏗️ ساختار پروژه

```text
├── .github/workflows/build-android.yml   # پایپ‌لاین خودکار GitHub Actions + تست + ساخت و بررسی APKها
├── native/gfproxy/                       # موتور Go ضد DPI (DoH + TLS Fragmentation + Unified HTTP/SOCKS5 Gateway + Kill-Switch)
├── scripts/
│   ├── fetch-greatfire-libs.sh           # دریافت رسمی cronet-108.0.5359.195.aar و IEnvoyProxy.aar (e3.5.0)
│   ├── build-gfproxy-android.sh          # کامپایل NDK API 21 + 16KB Page-Aligned برای ۴ پردازنده
│   └── ensure-keystore.sh                # تولید/پیکربندی امضای V1+V2+V3 برای نصب روی اندروید ۵ تا ۱۶
└── app/
    └── src/main/kotlin/org/greatfire/browser/
        ├── envoy/                        # اتصال به Cronet پچ‌شده GreatFire، IEnvoyProxy، Shadowsocks-Rust و GfProxyDaemon
        ├── vpn/                          # مدیریت هوشمند تونل‌ها، Kill-Switch، اشتراک‌ها و پل گواهی اندرویدهای قدیمی
        ├── web/                          # پروکسی سطح موتور WebView، سپر ضد نشت WebRTC، AdBlock و مدیریت تب‌ها
        └── ui/                           # رابط کاربری فارسی/انگلیسی مدرن، پنل سرورها و دانلود امن درون تونل
```

---

## 📜 مجوز (License)

منتشرشده تحت مجوز متن‌باز **GNU GPL v3.0** — هم‌راستا با پروژه‌های [`greatfire/envoy`](https://github.com/greatfire/envoy)، [`greatfire/IEnvoyProxy`](https://github.com/greatfire/IEnvoyProxy) و [`greatfire/freebrowser`](https://github.com/greatfire/freebrowser).
