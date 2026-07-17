# Firstham AetherGui

Firstham AetherGui یک رابط دسکتاپ مستقل و ساده برای ویندوز بر پایهٔ Tauri است که هستهٔ رسمی [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) را به‌صورت sidecar مخفی و کنترل‌شده اجرا می‌کند. این repository فورک Aether نیست و منطق شبکه، اسکن، تونل و مبهم‌سازی را دوباره پیاده‌سازی نمی‌کند.

[English](README.md)

برای نصب، تنظیمات، رفع اشکال و اتصال به v2rayN یا Proxifier، [راهنمای کامل فارسی](docs/GUIDE.fa.md) را بخوانید.

## امکانات

- حالت مستقل VPN سراسری با موتور TUN داخلی sing-box و checksum تأییدشده
- تمام ترافیک، عبور مستقیم شبکهٔ محلی و تونل انتخابی واقعی بر پایهٔ مسیر پردازش
- محافظت نشت DNS، کنترل صریح IPv6، قطع اضطراری اختیاری و بازیابی پس از خطا
- حالت SOCKS5 دستی حفظ شده است؛ v2rayN و Proxifier لازم نیستند

- اتصال، قطع اتصال، reconnect و نمایش وضعیت دقیق
- پروتکل‌های MASQUE، WireGuard و gool / WARP-in-WARP
- حالت‌های اسکن Turbo، Balanced، Thorough و Stealth
- IPv4، IPv6 و Both
- پروفایل‌های obfuscation متناسب با پروتکل
- انتخاب HTTP/3 یا HTTP/2 برای MASQUE
- تنظیمات پیشرفته همراه با اعتبارسنجی و هشدار listener غیرمحلی
- لاگ زنده، کپی/پاک‌کردن و تست اتصال SOCKS5
- ذخیرهٔ تنظیمات، بازنشانی پیش‌فرض‌ها و System Tray
- جلوگیری از اجرای چند نمونه و پایان تمیز پردازش Aether
- رابط جمع‌وجور با تمرکز بر اتصال و بخش جداگانهٔ عیب‌یابی
- پشتیبانی کامل فارسی و انگلیسی با ذخیرهٔ زبان انتخابی
- چیدمان راست‌به‌چپ و فونت Vazirmatn داخلی
- ترجمهٔ منوی Tray، راهنمای شروع، tooltipها، وضعیت‌ها و مستندات داخلی

## دانلود

فایل نصب `.exe`، بستهٔ `.msi` و نسخهٔ portable از بخش [Releases](https://github.com/hamvex/AetherGUI/releases) قابل دریافت هستند.

در حال حاضر Windows x64 پشتیبانی می‌شود. فایل‌های بدون امضای دیجیتال ممکن است هشدار SmartScreen نشان دهند.

## توسعه و ساخت

```powershell
npm ci
npm run fetch:core
npm test
cargo test --manifest-path src-tauri/Cargo.toml --locked
npm run build
```

اسکریپت `fetch:core` فایل رسمی Aether v1.2.0 را از GitHub Release دریافت و SHA-256 آن را پیش از استفاده بررسی می‌کند. خروجی installer در `src-tauri/target/release/bundle/` قرار می‌گیرد.

## نکات پیاده‌سازی VPN سراسری

حالت VPN از موتور TUN داخلی sing-box 1.13.14 با نسخه و هش ثابت استفاده می‌کند و پراکسی SOCKS5 محلی Aether تنها upstream آن است. رابط گرافیکی با دسترسی معمولی اجرا می‌شود و فقط helper محدود برای ساخت رابط مجازی و اعمال route درخواست دسترسی مدیر می‌کند. قوانین Include/Exclude بر پایهٔ مسیر واقعی پردازش در ویندوز هستند. DNS داخل تونل حل می‌شود و IPv6 یا از تونل عبور می‌کند یا صریحاً مسدود می‌شود.

Kill Switch به‌صورت پیش‌فرض خاموش است. در این نسخه فقط در طول نشست فعال است: هنگام راه‌اندازی مجدد listener مربوط به Aether، مسیر سخت‌گیرانهٔ TUN/WFP را نگه می‌دارد و در قطع عمدی یا بازیابی حذف می‌کند. این قابلیت فایروال دائمی هنگام بوت نیست و پیش از شروع helper یا پس از خاتمهٔ اجباری موتور مسیریابی تضمینی ایجاد نمی‌کند. پس از نشست ناقص از `FirsthamAetherGui.exe --repair-network` استفاده کنید.

تست‌های خودکار نگاشت تنظیمات، handshake پراکسی، چرخهٔ پردازش، بازیابی و اعتبار پیکربندی sing-box را پوشش می‌دهند. تعویض زندهٔ Wi-Fi/Ethernet، خواب و بیداری، reboot هنگام اتصال و Windows 10/11 باید طبق چک‌لیست انتشار روی دستگاه فیزیکی آزمایش شوند و در CI به‌صورت امن قابل شبیه‌سازی نیستند.

## مجوز

GNU Affero General Public License v3.0. هستهٔ Aether متعلق به پروژهٔ CluvexStudio/Aether است و این repository فقط frontend مستقل آن است.
