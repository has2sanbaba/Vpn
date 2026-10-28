# 🛡️ مرورگر ضد فیلتر GreatFire VPN Browser v2.0 (مبتنی بر `greatfire/envoy`)

مرورگر اندروید متن‌باز، امن و ضد سانسور ساخته‌شده بر پایه پروژه‌های رسمی سازمان **[GreatFire](https://github.com/greatfire)** (`envoy`، `IEnvoyProxy` و `freebrowser`) به همراه موتورهای بومی تغییر آی‌پی واقعی (`WARP+`، `Tor + GreatFire Bridges` و `Sing-Box`) که **۱۰۰٪ ترافیک تمام وب‌سایت‌ها، یوتیوب، شبکه‌های اجتماعی، وب‌سوکت‌ها و دانلود فایل‌ها** را بدون نشت آی‌پی از فیلترینگ IP و SNI عبور می‌دهد.

---

## 🚀 تغییرات بزرگ نسخه v2.0.0 (رفع کامل باگ‌های اتصال و باز نشدن سایت‌های فیلتر)

1. **موتور هفت‌گانه واقعی ضد فیلترینگ (۲۸ کتابخانه بومی `.so` برای ۴ معماری پردازنده):**
   - **`libwarpplus.so` (`bepass-org/warp-plus` v1.2.6):** موتور **بدون سرور (Zero-Config)** با اسکن خودکار آی‌پی تمیز Cloudflare، حالت دو لایه **WARP-in-WARP (`Gool`)** و حالت **Psiphon-over-WARP (`CFON` آلمان)** برای تغییر واقعی آی‌پی و باز کردن یوتیوب و تلگرام با سرعت بالا.
   - **`libgojni.so` (`GreatFire IEnvoyProxy` e3.5.0) + `libtor.so` (`Guardian Project TorService` 0.4.8.12):** زنجیره‌سازی استاندارد و کامل Pluggable Transportهای GreatFire (**`MeekLite` CDN77 HTTPS**، **`Snowflake` CDN77/DataPacket WebRTC** و **`Obfs4` Lyrebird**) در پشت هسته اصلی Tor برای شرایط قطع کامل UDP.
   - **`libsingbox.so` (`SagerNet/sing-box` v1.14.4):** پشتیبانی کامل از **`VLESS` (`XTLS-Reality`, `WS`, `gRPC`, `HTTPUpgrade`)**، **`Trojan`**، **`TUIC v5`**، **`Hysteria2`**، **`VMess`**، **`Shadowsocks-2022`** و **`WireGuard`** به همراه **`record_fragment: true`** داخلی و دریافت خودکار سرورهای رایگان روز.
   - **`libcronet.108.0.5359.195.so` (`GreatFire Envoy`):** موتور شبکه کرومیوم پچ‌شده با استراتژی ضد DPI سطح پکت Geneva و ECH.
   - **`libsslocal.so` (`GreatFire Shadowsocks-Rust`):** کلاینت سریع Shadowsocks.
   - **`libgfproxy.so` (درگاه یکپارچه محلی HTTP/SOCKS5 + Anti-DPI):** پشتیبانی کامل از گواهی‌های ریشه اندروید ۵ تا ۱۶ (`/apex/com.android.conscrypt/cacerts` + `cacert.pem`)، مسابقه هم‌زمان بین سرورهای DoH (`8.8.8.8`, `9.9.9.11`, `94.140.14.14`, `1.1.1.1`) و فیلتر آی‌پی‌های مسدودسازی (`10.10.34.*`, `100.64.*`).

2. **تضمین ۱۰۰٪ عدم نشت و تأیید واقعی باز شدن یوتیوب قبل از نمایش «متصل»:**
   - اجرای `ProxyController.setProxyOverride` دقیقاً روی `Dispatchers.Main.immediate`.
   - قفل Kill-Switch از لحظه صفر شروع برنامه (جلوگیری از نشت پکت در وضعیت `CONNECTING`) و بارگذاری خودکار سایت بازشده به محض برقراری تونل.
   - تست واقعی باز شدن **`https://www.youtube.com/generate_204`** و **`https://x.com/robots.txt`** پیش از اعلام وضعیت `CONNECTED` و سوییچ خودکار بین موتورها در حالت **`Smart Auto`**.

3. **قابل نصب روی تمام گوشی‌های اندروید و تمام پردازنده‌ها:**
   - **نسخه‌های اندروید:** از **Android 5.0 Lollipop (`API 21`)** تا **Android 16 (`API 35+`)** با امضای هم‌زمان **V1 + V2 + V3** و ترازبندی حافظه **16KB Page Size**.
   - **تمام معماری‌های پردازنده:** `arm64-v8a`، `armeabi-v7a`، `x86_64`، `x86` و نسخه جامع `universal`.

---

## 📥 دانلود مستقیم فایل نصبی (APK)

فایل‌های نصبی نسخه **v2.0.0** هم در بخش **[Releases](../../releases)** و هم در بخش **[Actions](../../actions)** به‌صورت خودکار منتشر می‌شوند:

- `GreatFire-VPN-Browser-v2.0.0-universal-release.apk` ← **پیشنهادی (قابل نصب روی تمام گوشی‌ها و پردازنده‌ها)**
- `GreatFire-VPN-Browser-v2.0.0-arm64-v8a-release.apk` ← سبک‌تر، مخصوص گوشی‌های ۶۴ بیتی جدید
- `GreatFire-VPN-Browser-v2.0.0-armeabi-v7a-release.apk` ← سبک‌تر، مخصوص گوشی‌های قدیمی ۳۲ بیتی
- `GreatFire-VPN-Browser-v2.0.0-x86_64-release.apk` / `x86-release.apk` ← مخصوص تبلت‌های اینتل، کروم‌بوک و شبیه‌سازها

---

## 🛠️ بیلد محلی (Local Build)

پیش‌نیازها: **JDK 17**، **Go 1.25+**، **Android SDK (Platform 35)** و **Android NDK (r26 / r27)**.

```bash
# ۱. دریافت کتابخانه‌های رسمی GreatFire (Envoy Cronet + IEnvoyProxy)
./scripts/fetch-greatfire-libs.sh

# ۲. کامپایل متقاطع هسته‌های بومی (libgfproxy.so + libwarpplus.so + libsingbox.so) برای هر ۴ پردازنده با API 21
./scripts/build-gfproxy-android.sh

# ۳. ساخت کلید امضای V1/V2/V3 و بیلد نهایی APKهای Release
./scripts/ensure-keystore.sh
./gradlew testDebugUnitTest assembleRelease
```
