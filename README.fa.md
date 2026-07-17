# Firstham AetherGui

Firstham AetherGui یک رابط دسکتاپ مستقل و ساده برای ویندوز بر پایهٔ Tauri است که هستهٔ رسمی [CluvexStudio/Aether](https://github.com/CluvexStudio/Aether) را به‌صورت sidecar مخفی و کنترل‌شده اجرا می‌کند. این repository فورک Aether نیست و منطق شبکه، اسکن، تونل و مبهم‌سازی را دوباره پیاده‌سازی نمی‌کند.

[English](README.md)

برای نصب، تنظیمات، رفع اشکال و اتصال به v2rayN یا Proxifier، [راهنمای کامل فارسی](docs/GUIDE.fa.md) را بخوانید.

## امکانات

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

## مجوز

GNU Affero General Public License v3.0. هستهٔ Aether متعلق به پروژهٔ CluvexStudio/Aether است و این repository فقط frontend مستقل آن است.
