# راهنمای کامل Firstham AetherGui

Firstham AetherGui رابط گرافیکی مستقل ویندوز برای هستهٔ رسمی [Aether](https://github.com/CluvexStudio/Aether) است. برنامه عملیات شبکه را دوباره پیاده‌سازی نمی‌کند؛ فایل رسمی `aether.exe` را بدون پنجرهٔ ترمینال اجرا می‌کند، تنظیمات را از طریق متغیرهای رسمی Aether می‌فرستد و چرخهٔ عمر، لاگ و خطاهای آن را مدیریت می‌کند.

نسخهٔ 1.4.0 این برنامه هستهٔ Aether v1.2.0 را در خود دارد. رابط کاربری این نسخه فشرده‌تر شده و ترجمهٔ کامل فارسی، چیدمان راست‌به‌چپ، فونت آفلاین Vazirmatn و راهنمای دوزبانهٔ داخل برنامه را اضافه می‌کند. تست مسیر دادهٔ MASQUE و WireGuard و انتخاب HTTP/3 یا HTTP/2 نیز حفظ شده است. جزئیات تغییرات هسته در [صفحهٔ انتشار رسمی](https://github.com/CluvexStudio/Aether/releases/tag/v1.2.0) موجود است.

## ۱. نصب و اجرای اولیه

### روش پیشنهادی: فایل Setup

1. از صفحهٔ [Releases پروژه](https://github.com/hamvex/AetherGUI/releases) فایل Setup مربوط به Firstham AetherGui را دریافت کنید.
2. فایل را اجرا و مراحل نصب را کامل کنید. اگر SmartScreen نمایش داده شد، ناشر فایل هنوز امضای تجاری ندارد؛ فقط فایلی را اجرا کنید که از Release رسمی همین مخزن گرفته‌اید و SHA-256 آن با `SHA256SUMS.txt` برابر است.
3. Firstham AetherGui را از Start Menu باز کنید.
4. برای شروع، تنظیمات پیش‌فرض را تغییر ندهید و روی **Connect** بزنید.
5. وقتی وضعیت **Connected** شد، آدرس محلی `127.0.0.1:1819` آمادهٔ استفاده است.
6. روی **Test connection** بزنید. نتیجهٔ Cloudflare trace باید در سمت راست نمایش داده شود.

### نسخهٔ Portable

فایل ZIP را در یک پوشهٔ ثابت استخراج کنید و `aether-gui.exe` را اجرا کنید. فایل `aether.exe` باید کنار آن باقی بماند. اجرای مستقیم برنامه از داخل ZIP یا جدا کردن این دو فایل پشتیبانی نمی‌شود.

### حذف برنامه

از **Settings > Apps > Installed apps > Firstham AetherGui > Uninstall** استفاده کنید. پیش از حذف، از منوی Tray گزینهٔ **Exit** را بزنید تا پردازش هسته تمیز بسته شود.

## ۲. آشنایی با صفحهٔ اصلی

### وضعیت اتصال

- **Disconnected:** اتصال فعال نیست.
- **Scanning:** برنامه در حال پیدا کردن endpoint مناسب است.
- **Connecting:** endpoint انتخاب شده و تونل در حال ساخته شدن است.
- **Connected:** SOCKS5 محلی آماده است.
- **Reconnecting:** اتصال قطع شده و بازیابی خودکار در حال انجام است.
- **Error:** هسته متوقف شده یا تنظیمات/شبکه خطا دارد؛ متن لاگ را بررسی کنید.

در بالای صفحه سه مقدار دیده می‌شود:

- **Endpoint:** سرور انتخاب‌شده یا endpoint سفارشی.
- **SOCKS5:** آدرس پروکسی محلی؛ پیش‌فرض `127.0.0.1:1819` است.
- **Elapsed:** مدت زمان اتصال فعلی.

دکمهٔ **Disconnect** تونل را می‌بندد. بستن پنجره برنامه را به System Tray می‌فرستد؛ برای خروج کامل از منوی آیکن Tray گزینهٔ **Exit** را انتخاب کنید.

## ۳. تنظیمات اتصال

### Protocol

- **MASQUE:** گزینهٔ پیش‌فرض و معمولاً بهترین نقطهٔ شروع. از تونل مبتنی بر HTTP/3 یا HTTP/2 استفاده می‌کند.
- **WireGuard:** تونل WireGuard با اسکن و obfuscation خود Aether.
- **gool:** حالت WARP-in-WARP؛ برای شرایطی که این زنجیره روی شبکه بهتر جواب می‌دهد.

اگر مطمئن نیستید، ابتدا MASQUE + HTTP/3 را امتحان کنید. اگر UDP/QUIC روی شبکه مسدود یا محدود است، MASQUE + HTTP/2 را انتخاب کنید.

### Scan mode

- **Turbo:** سریع‌ترین اسکن؛ مناسب شبکهٔ خوب.
- **Balanced:** تعادل سرعت و دقت و گزینهٔ پیشنهادی روزمره.
- **Thorough:** بررسی گسترده‌تر؛ کندتر ولی مناسب زمانی که endpoint خوب به‌سختی پیدا می‌شود.
- **Stealth:** اسکن محافظه‌کارانه‌تر برای شبکه‌های حساس.

### IP scan

- **IPv4:** سازگارترین انتخاب و پیش‌فرض.
- **IPv6:** فقط وقتی اتصال IPv6 واقعی و پایدار دارید.
- **Both:** اسکن هر دو خانواده؛ ممکن است زمان اتصال بیشتر شود.

### Obfuscation

گزینه‌ها با پروتکل تغییر می‌کنند:

- برای MASQUE: **Firewall**، **GFW** و **Off**.
- برای WireGuard و gool: **Balanced**، **Aggressive**، **Light** و **Off**.

ابتدا پروفایل متعادل/پیش‌فرض را استفاده کنید. حالت Aggressive سربار بیشتری دارد و فقط زمانی مفید است که حالت‌های سبک‌تر کار نمی‌کنند.

### MASQUE transport

- **HTTP/3 · QUIC:** سریع‌تر و پیش‌فرض؛ به UDP سالم نیاز دارد.
- **HTTP/2 · TCP:** برای شبکه‌ای که UDP یا QUIC را مسدود/کند می‌کند. این انتخاب به متغیر رسمی `AETHER_MASQUE_HTTP2` نگاشت می‌شود.

## ۴. Advanced settings

- **SOCKS5 listen address:** آدرس و پورت پروکسی محلی. برای استفاده شخصی همان `127.0.0.1:1819` را نگه دارید.
- **I understand...:** فقط برای listener غیرمحلی لازم است. قرار دادن `0.0.0.0` پروکسی را در معرض شبکه قرار می‌دهد و بدون فایروال/احراز هویت خطرناک است.
- **Custom endpoint:** endpoint به شکل `IP:port`. نام دامنه پذیرفته نمی‌شود. خالی بگذارید تا Aether اسکن کند.
- **WireGuard keepalive:** فاصلهٔ keepalive بر حسب ثانیه؛ مقدار پیش‌فرض ۵ مناسب است.
- **Connect stall timeout:** زمانی که GUI پیش از متوقف کردن اتصال گیرکرده صبر می‌کند.
- **Watchdog:** پردازش هسته‌ای را که در اتصال گیر کرده متوقف می‌کند؛ روشن بماند.
- **Quick reconnect:** endpoint تأییدشدهٔ قبلی را برای اتصال مجدد امتحان می‌کند.
- **Base/WireGuard/MASQUE configuration file:** مسیر فایل‌های config سازگار با Aether. مسیرها ذخیره می‌شوند، ولی GUI کلید خصوصی یا credential را در تنظیمات خود نگه نمی‌دارد.

دکمهٔ **Reset defaults** همهٔ انتخاب‌های GUI را به پیش‌فرض برمی‌گرداند.

## ۵. لاگ و عیب‌یابی

- **Live logs:** خروجی استاندارد و خطای هسته را زنده نشان می‌دهد.
- **Copy:** کل لاگ را برای گزارش خطا کپی می‌کند.
- **Clear:** فقط نمایش لاگ را پاک می‌کند و اتصال را تغییر نمی‌دهد.
- **Test connection:** درخواست HTTPS را از SOCKS5 فعلی به Cloudflare trace می‌فرستد و نتیجه را خوانا نمایش می‌دهد. این تست TLS را غیرفعال نمی‌کند.

اگر اتصال برقرار نشد:

1. مطمئن شوید نمونهٔ دیگری از Aether یا برنامه‌ای روی پورت 1819 اجرا نیست.
2. MASQUE را یک بار با HTTP/3 و یک بار با HTTP/2 امتحان کنید.
3. Scan mode را از Balanced به Thorough تغییر دهید.
4. در شبکهٔ بدون IPv6، IP scan را روی IPv4 بگذارید.
5. endpoint سفارشی و مسیرهای config را خالی کنید و دوباره تست بگیرید.
6. فایروال یا آنتی‌ویروس را بررسی کنید؛ `aether-gui.exe` و `aether.exe` باید اجازهٔ اتصال خروجی داشته باشند.
7. لاگ را Copy کنید، اطلاعات شخصی احتمالی را حذف کنید و در [Issues](https://github.com/hamvex/AetherGUI/issues) گزارش دهید.

## ۶. استفاده در v2rayN

ابتدا Firstham AetherGui را **Connect** کنید و تا نمایش **Connected** صبر کنید. v2rayN را طوری تنظیم نکنید که خود `aether.exe` را دوباره از همان زنجیره عبور دهد؛ این کار حلقهٔ پروکسی می‌سازد.

### روش A: افزودن Aether به‌عنوان سرور SOCKS

این روش برای استفاده از خروجی Aether در رابط و routing خود v2rayN است:

1. در v2rayN گزینهٔ افزودن سرور SOCKS را باز کنید (در نسخه‌های مختلف نام منو ممکن است **Add SOCKS server** یا مشابه آن باشد).
2. Remarks را `Aether` بگذارید.
3. Address را `127.0.0.1` وارد کنید.
4. Port را `1819` وارد کنید.
5. Username و Password را خالی بگذارید.
6. ذخیره کنید و پروفایل Aether را فعال کنید.
7. برای برنامه‌های معمولی، routing/system proxy v2rayN را مطابق نیاز خود فعال کنید.

توجه: System Proxy ویندوز عموماً HTTP را بهتر از SOCKS پشتیبانی می‌کند؛ برای اعمال SOCKS روی برنامه‌های دلخواه، Proxifier یا قابلیت TUN خود v2rayN انتخاب مناسب‌تری است. راهنمای رسمی routing در [ویکی v2rayN](https://github.com/2dust/v2rayN/wiki/Description-of-system-proxy-routing) موجود است.

### روش B: Aether به‌عنوان Front Proxy برای نودهای v2rayN

این حالت مسیر زیر را می‌سازد:

`برنامه → v2rayN → Aether SOCKS5 → نود انتخابی v2rayN → اینترنت`

1. ابتدا طبق روش A یک پروفایل SOCKS با نام/alias مشخص مثل `Aether` بسازید.
2. تنظیمات گروه subscription یا پروفایل مقصد را باز کنید.
3. مقدار **Front proxy alias** را روی alias مربوط به Aether قرار دهید.
4. نود اصلی v2rayN را انتخاب و اتصال را آزمایش کنید.

نام و محل این گزینه با نسخهٔ v2rayN تغییر می‌کند؛ منطق رسمی Proxy Chain در [راهنمای v2rayN](https://github.com/2dust/v2rayN/wiki/Description-of-proxy-chain) توضیح داده شده است.

### جلوگیری از حلقه در v2rayN

- فقط یکی از دو روش بالا را برای یک مسیر استفاده کنید.
- Aether را هم‌زمان به‌عنوان ورودی و خروجی همان زنجیره قرار ندهید.
- اگر TUN فعال است، برای `aether-gui.exe` و `aether.exe` مسیر Direct/bypass تعریف کنید.
- ابتدا Firstham AetherGui را وصل و سپس v2rayN را راه‌اندازی کنید؛ هنگام خروج ترتیب را برعکس انجام دهید.

## ۷. استفاده در Proxifier

Proxifier برای هدایت اتصال TCP برنامه‌هایی که تنظیم پروکسی ندارند مناسب است. مستندات رسمی در [Proxifier for Windows](https://www.proxifier.com/docs/win-v4/index.html) موجود است.

### افزودن SOCKS5

1. Firstham AetherGui را وصل کنید و **Verify connection** را با موفقیت اجرا کنید.
2. در Proxifier به **Profile > Proxy Servers** بروید.
3. روی **Add** بزنید.
4. Address: `127.0.0.1`
5. Port: `1819`
6. Protocol: **SOCKS Version 5**
7. Authentication را خاموش/خالی بگذارید.
8. با **Check** اتصال را تست و سپس ذخیره کنید.

### تعریف Rule امن

ترتیب Ruleها مهم است و اولین Rule منطبق اجرا می‌شود:

1. در **Profile > Proxification Rules** یک Rule در بالاترین جایگاه با نام `Aether Direct` بسازید.
2. Applications را شامل `aether-gui.exe; aether.exe` کنید و Action را **Direct** بگذارید. این Rule جلوی حلقه را می‌گیرد.
3. Rule داخلی Localhost را Direct نگه دارید.
4. برای استفادهٔ انتخابی، یک Rule زیر آن بسازید، فایل‌های EXE موردنظر را انتخاب و Action را روی proxy با نام Aether قرار دهید.
5. برای همهٔ اتصال‌های TCP، Action مربوط به Default را روی Aether بگذارید؛ این حالت را فقط پس از ساخت Rule مستقیم مرحلهٔ ۱ فعال کنید.

### DNS در Proxifier

در **Profile > Name Resolution** گزینهٔ resolve کردن hostname از طریق proxy را فعال کنید تا برنامه قبل از رسیدن به SOCKS5، DNS را مستقیم resolve نکند. اگر نرم‌افزار مقصد DNS اختصاصی یا UDP استفاده کند، رفتار آن ممکن است متفاوت باشد.

Proxifier عمدتاً اتصال‌های TCP را هدایت می‌کند؛ برنامه‌ای که فقط UDP استفاده می‌کند با یک SOCKS5 معمولی از این مسیر عبور نمی‌کند. برای چنین برنامه‌ای از راهکار TUN سازگار استفاده کنید.

## ۸. تنظیم دستی در نرم‌افزارهای دارای SOCKS5

در هر برنامه‌ای که SOCKS5 را مستقیم پشتیبانی می‌کند این مقادیر را وارد کنید:

- Type: SOCKS5
- Host: `127.0.0.1`
- Port: `1819`
- Authentication: None
- Proxy DNS / Remote DNS: روشن، اگر موجود است

در Firefox می‌توانید از **Settings > Network Settings > Manual proxy configuration** استفاده کنید، SOCKS Host و Port را وارد کنید، SOCKS v5 و گزینهٔ Proxy DNS را فعال کنید.

## ۹. امنیت و حریم خصوصی

- listener را بدون نیاز واقعی از localhost خارج نکنید.
- فایل‌های config و identity هسته را عمومی نکنید.
- Firstham AetherGui اعتبارسنجی TLS را خاموش نمی‌کند و ورودی کاربر را در shell اجرا نمی‌کند.
- نسخه‌های فعلی امضای تجاری Windows ندارند؛ checksum فایل Release را بررسی کنید.
- این ابزار تضمین ناشناس‌بودن کامل نمی‌دهد؛ رفتار مرورگر، DNS، حساب کاربری و fingerprint مستقل از تونل هستند.

برای بررسی SHA-256 در PowerShell:

```powershell
Get-FileHash ".\Firstham AetherGui_1.4.0_x64-setup.exe" -Algorithm SHA256
```

## ۱۰. ساخت از سورس

نیازمندی‌ها: Windows 10/11 x64، Node.js 20 یا جدیدتر، Rust stable با target مربوط به MSVC، Visual Studio C++ Build Tools و WebView2.

```powershell
git clone https://github.com/hamvex/AetherGUI.git
cd AetherGUI
npm ci
npm run fetch:core
npm test
cargo test --manifest-path src-tauri/Cargo.toml --locked
npm run build
```

اسکریپت fetch فایل رسمی Aether v1.2.0 و checksum ناشر را دریافت می‌کند و فقط پس از تطبیق SHA-256 آن را وارد build می‌کند. خروجی‌ها در این مسیرها ساخته می‌شوند:

- NSIS: `src-tauri/target/release/bundle/nsis/`
- MSI: `src-tauri/target/release/bundle/msi/`
- Executable: `src-tauri/target/release/aether-gui.exe`

برای توسعه:

```powershell
npm run dev
```

برای استفاده از هستهٔ محلی آزمایشی:

```powershell
$env:AETHER_CORE_BINARY = "C:\path\to\aether.exe"
npm run build
```

این override فقط برای توسعه است؛ نسخهٔ منتشرشده باید از هستهٔ رسمی checksum‌شده ساخته شود.
