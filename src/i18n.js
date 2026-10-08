// i18n dictionary for the Aethon Windows UI (Android dev.020 information
// architecture: HOME / CONFIGURATIONS / MORE SETTINGS / SETTINGS / ABOUT + Help).
// English and Persian are both first-class: every visible string has both keys, no
// fallback English inside Persian (product/protocol names excepted), natural wording.

const en = {
  // Brand + navigation
  'brand.subtitle': 'Private network',
  'nav.home': 'Home', 'nav.configurations': 'Configurations', 'nav.more': 'More Settings',
  'nav.settings': 'Settings', 'nav.diagnostics': 'Diagnostics', 'nav.about': 'About',

  // Actions
  'actions.connect': 'CONNECT', 'actions.disconnect': 'DISCONNECT', 'actions.reset': 'Reset Defaults',
  'actions.apply': 'Apply', 'actions.selectAll': 'Select All', 'actions.clearAll': 'Clear All',
  'actions.cancel': 'Cancel', 'actions.confirmReset': 'Reset',

  // Connection states
  'status.disconnected': 'Aethon Ready', 'status.connected': 'Aethon Active',
  'status.connecting': 'Connecting…', 'status.disconnecting': 'Disconnecting…',
  'status.scanning': 'Scanning…', 'status.reconnecting': 'Reconnecting…',
  'status.error': 'Connection error',
  'connection.ready': '', 'connection.connected': '', 'connection.connecting': 'Preparing a secure route.',
  'connection.disconnecting': 'Restoring your network.', 'connection.reconnecting': 'Recovering the secure route.',
  'connection.error': 'Aethon needs attention.',

  // Home telemetry
  'traffic.upload': 'Upload', 'traffic.download': 'Download', 'traffic.ping': 'Ping',
  'traffic.time': 'TIME', 'traffic.location': 'Location',
  'traffic.locationUnavailable': 'Location unavailable', 'traffic.locationDetecting': 'Detecting…',

  // Home Psiphon country selector
  'location.auto': 'AUTO', 'location.autoFull': 'Automatic',
  'location.manual': 'Manual',

  // Configurations — common settings
  'config.mode': 'Connection Mode', 'config.vpn': 'Device VPN', 'config.proxy': 'SOCKS5 Proxy',
  'config.protocol': 'Protocol',
  'config.transport': 'MASQUE Connection Method', 'config.transportH3': 'HTTP/3 (QUIC)', 'config.transportH2': 'HTTP/2 (TCP)',
  'config.goolMode': 'gool Topology', 'config.goolModeMasque': 'MASQUE-carried', 'config.goolModeClassic': 'Classic (WARP-in-WARP)',
  'config.scan': 'Scan Mode',
  'config.psiphonTransport': 'Psiphon Transport', 'config.psiphonTransportAuto': 'Auto', 'config.psiphonTransportDirect': 'Direct',
  'config.psiphonLocation': 'Psiphon Location', 'config.psiphonAuto': 'Automatic',
  'config.endpoint': 'Custom Endpoint',
  'config.reconnect': 'Auto Reconnect', 'config.reconnectHelp': 'Recover after temporary failures',
  'config.lan': 'Connection from LAN', 'config.lanHelp': 'Allow trusted private-network devices to use Aethon',
  'config.dns': 'Private DNS Routing', 'config.dnsHelp': 'Route DNS through Aethon',
  'config.kill': 'Fail Closed', 'config.killHelp': 'Block traffic while recovering',
  'config.mtu': 'VPN MTU', 'config.mtuHelp': 'Packet size carried by the Aethon adapter (capped automatically per protocol)',
  'config.lanDisabled': 'LAN sharing disabled', 'config.lanUnavailable': 'LAN status unavailable',

  // Configurations — scan mode options
  'scan.turbo': 'Turbo', 'scan.balanced': 'Balanced', 'scan.thorough': 'Thorough',
  'scan.stealth': 'Verified', 'scan.ironclad': 'Ironclad',

  // More Settings — section headers (Android dev.020 seven-category structure)
  'more.performance': 'Performance', 'more.privacy': 'Privacy & Security', 'more.network': 'Network & Routing',
  'more.proxy': 'Proxy & Chaining', 'more.protocols': 'Advanced Protocols',
  'more.organization': 'Organization / Zero Trust', 'more.help': 'Help',

  // More Settings — Performance
  'config.ech': 'ECH (Encrypted Client Hello)', 'config.echOff': 'Off', 'config.echAuto': 'Auto', 'config.echCustom': 'Custom (Base64)', 'config.echCustomLabel': 'ECH Config (Base64)',
  'config.h2Fragment': 'TLS ClientHello Fragmentation (HTTP/2)', 'config.h2FragmentHelp': 'Split ClientHello to evade DPI on h2',
  'config.h2FragmentSize': 'Fragment Size (bytes)', 'config.h2FragmentDelay': 'Fragment Delay (ms)',
  'config.noDataCheck': 'Skip Data Validation', 'config.noDataCheckHelp': 'Disable end-to-end probe (faster, less reliable)',
  'config.validateSecs': 'Validation Timeout (seconds)', 'config.startupSecs': 'Startup Deadline (seconds)', 'config.reconnectSecs': 'Reconnect Delay (seconds)',

  // More Settings — Network & Routing
  'config.ipv6': 'IPv6 Behavior', 'config.ipv6Tunnel': 'Tunnel', 'config.ipv6Block': 'Block',
  'config.routeSniff': 'Route SNI/Host Sniffing', 'config.routeSniffHelp': 'Read server name from first bytes for routing rules',
  'config.routeSniffMs': 'Sniff Timeout (ms)',

  // More Settings — Proxy & Chaining
  'config.upstreamProxy': 'Upstream Proxy', 'config.upstreamProxyHelp': 'Chain Aether behind another proxy (not available with Psiphon or Tor protocols)',

  // More Settings — Advanced Protocols
  'config.wiwEndpoints': 'WARP-in-WARP Endpoints (classic gool)', 'config.wiwEndpointsHelp': 'Optional outer/inner hops; setting either selects the classic gool topology',
  'config.mimOuter': 'MIM Outer Endpoint', 'config.mimInner': 'MIM Inner Endpoint',
  'config.quicV2': 'QUIC v2 opener', 'config.quicV2Help': 'Allow the core to use its v2 negotiation opener for MASQUE',
  'config.wgNoProfileRetry': 'WireGuard: No Profile Retry', 'config.wgNoProfileRetryHelp': 'Don\'t retry other obfuscation profiles on scan failure',

  // More Settings — Organization / Zero Trust
  'config.zeroTrust': 'Zero Trust (Team)',
  'config.team': 'Team Name', 'config.accessEmail': 'Access Email', 'config.accessToken': 'Access Token',
  'config.gateway': 'Use Gateway Proxy', 'config.gatewayHelp': 'Route HTTP/HTTPS through organization gateway',

  // Help (complete consumer guide, Android dev.020 content parity)
  'help.mode': 'Connection Mode',
  'help.mode.body': 'Device VPN routes all of Windows through Aethon automatically — browsers, apps, and DNS. SOCKS5 Proxy instead exposes local proxy ports (HTTP 127.0.0.1:1818, SOCKS5 127.0.0.1:1819) that you point applications at yourself. Your protocol choice is kept when switching modes.',
  'help.protocol': 'Protocol',
  'help.protocol.body': 'Smart Connect picks the protocol that connects fastest. WireGuard, gool, and MASQUE are the base transports; MIM chains two MASQUE hops. gool has two topologies since Core v2.3.0 (see gool Topology). Entries ending in "+ Psiphon" or "+ Tor" carry your traffic through that privacy chain after the base tunnel — nothing else needs to be enabled for them. Default: WireGuard + Psiphon.',
  'help.psiphon': '+ Psiphon',
  'help.psiphon.body': 'Traffic leaves through the Psiphon network, so the site you visit sees a Psiphon exit instead of the tunnel underlay. Your Home location shows the real Psiphon exit. Works with WireGuard, gool, and MASQUE.',
  'help.tor': '+ Tor',
  'help.tor.body': 'Traffic leaves through the Tor network. Your location on Home shows the real Tor exit country (read-only). Works with WireGuard, gool, and MASQUE. First connections can take longer while Tor downloads its directory.',
  'help.masqueMethod': 'MASQUE Connection Method',
  'help.masqueMethod.body': 'Chooses how MASQUE connects: HTTP/3 (QUIC) or HTTP/2 (TCP). Default is HTTP/2; if the network blocks one, the other often still works. The setting applies to MASQUE and MIM and is remembered when you switch protocols.',
  'help.goolMode': 'gool Topology',
  'help.goolMode.body': 'Aether Core v2.3.0 builds gool two ways. MASQUE-carried (the Core default) carries a WireGuard tunnel inside a MASQUE tunnel, which changes the exit address; it needs the network to let a MASQUE gateway scan through. Classic (WARP-in-WARP) nests WireGuard inside WireGuard and keeps a plain WARP exit; it works on networks that block the MASQUE scan. If the MASQUE-carried gool cannot find a gateway, a single connect falls back to Classic once, and the topology actually running is stated in the connected message — never silently swapped. The choice you make here is what runs; the fallback only ever selects the other topology when yours cannot scan on this network, and it remembers the topology that worked. Setting a WARP-in-WARP endpoint always selects Classic.',
  'help.scan': 'Scan Mode',
  'help.scan.body': 'How thoroughly Aethon searches for a working endpoint. Turbo is fastest, Ironclad is the most persistent on heavily filtered networks, and Verified (previously Stealth) dials only edges measured to answer. Default: Balanced.',
  'help.psiphonTransport': 'Psiphon Transport',
  'help.psiphonTransport.body': 'How the Psiphon helper reaches its network inside the tunnel. Auto picks per conditions; Direct avoids web-fronted relays. Default: Auto.',
  'help.psiphonLocation': 'Psiphon Location',
  'help.psiphonLocation.body': 'Chooses the country your traffic leaves from when a + Psiphon protocol is selected. Automatic lets Psiphon decide. The Home selector and this setting are the same choice.',
  'help.split': 'Split Tunneling',
  'help.split.body': 'Include mode sends only the selected applications through Aethon; Exclude mode sends everything except them. System applications are hidden unless you enable Show system apps; their selections are kept when hidden again.',
  'help.mtu': 'VPN MTU',
  'help.mtu.body': 'The packet size of the Aethon adapter. Aethon caps it automatically to what the selected protocol can carry; change it only for specific network problems.',
  'help.ech': 'ECH (Encrypted Client Hello)',
  'help.ech.body': 'Encrypts the server name in the TLS handshake. Auto uses it where supported; Custom accepts a base64 configuration.',
  'help.ipv6': 'IPv6 Behavior',
  'help.ipv6.body': 'Tunnel routes IPv6 alongside IPv4 when the core supports it; Block keeps IPv6 off so it cannot leak around the tunnel.',
  'help.reset': 'Reset Defaults',
  'help.reset.body': 'Restores Aethon\'s configuration defaults (WireGuard + Psiphon, Auto, Automatic, HTTP/2). Your language, theme, and daily connected time are not configuration and are kept.',

  // Reset dialog
  'reset.title': 'Reset settings?', 'reset.body': 'This will restore Aethon\'s configuration defaults.',
  'reset.cancel': 'Cancel', 'reset.confirm': 'Reset',

  // Split tunneling
  'split.title': 'Split Tunneling', 'split.help': 'Choose which applications use Aethon',
  'split.include': 'Include Apps', 'split.exclude': 'Exclude Apps',
  'split.select': 'Select Applications', 'split.search': 'Search applications', 'split.add': 'Add Executable',
  'split.systemApps': 'Show system apps',
  'split.missing': 'Missing application',

  // Settings page
  'settings.appearance': 'Appearance', 'settings.system': 'System', 'settings.light': 'Light', 'settings.dark': 'Dark',
  'settings.language': 'Language',
  'settings.notifications': 'Notifications', 'settings.notificationsHelp': 'Manage VPN and update notifications',
  'settings.manageNotifications': 'Manage Notifications',
  'settings.support': 'Support', 'settings.supportHelp': 'Connection details and status for troubleshooting',
  'settings.openDiagnostics': 'Advanced Diagnostics',
  'settings.orbStyle': 'Orb Style', 'settings.orbClassic': 'Classic', 'settings.orbMercury': 'Living Mercury',
  'settings.backup': 'Backup & Restore', 'settings.backupHelp': 'Export or restore your Aethon configuration',
  'settings.export': 'Export Configuration', 'settings.import': 'Import Configuration',

  // Updates
  'updates.automatic': 'Automatic Updates', 'updates.automaticHelp': 'Check silently in the background',
  'updates.current': 'Current Version', 'updates.notChecked': 'Not checked', 'updates.check': 'Check for Updates',
  'updates.download': 'Download', 'updates.install': 'Install', 'updates.checking': 'Checking for updates',
  'updates.upToDate': 'Up to date', 'updates.available': 'Update available', 'updates.ready': 'Ready to install',
  'updates.failed': 'Update failed',

  // About
  'about.title': 'About', 'about.original': 'Original Project: Aether',
  'about.originalCredit': 'Based on the original Aether project by CluvexStudio.',
  'about.application': 'Application: Aethon VPN',
  'about.applicationCredit': 'Android/Windows application and interface developed for Aether.',
  'about.version': 'Version', 'about.coreVersion': 'Aether Core',
  'about.telegram': 'Telegram community',

  // Picker / toasts
  'picker.empty': 'No applications found', 'picker.added': 'Executable added', 'picker.selected': 'selected',
  'toast.updated': 'Aethon is up to date', 'toast.telegram': 'Telegram is unavailable; opened the browser.',
  'toast.backupSaved': 'Configuration exported', 'toast.backupLoaded': 'Configuration imported', 'toast.backupInvalid': 'The backup file is not valid',
};

const fa = {
  // برند و ناوبری
  'brand.subtitle': 'شبکه خصوصی',
  'nav.home': 'خانه', 'nav.configurations': 'پیکربندی‌ها', 'nav.more': 'تنظیمات بیشتر',
  'nav.settings': 'تنظیمات', 'nav.diagnostics': 'عیب‌یابی', 'nav.about': 'درباره',

  // عملیات
  'actions.connect': 'اتصال', 'actions.disconnect': 'قطع اتصال', 'actions.reset': 'بازنشانی پیش‌فرض‌ها',
  'actions.apply': 'اعمال', 'actions.selectAll': 'انتخاب همه', 'actions.clearAll': 'پاک کردن همه',
  'actions.cancel': 'لغو', 'actions.confirmReset': 'بازنشانی',

  // وضعیت اتصال
  'status.disconnected': 'Aethon آماده است', 'status.connected': 'Aethon فعال است',
  'status.connecting': 'در حال اتصال…', 'status.disconnecting': 'در حال قطع اتصال…',
  'status.scanning': 'در حال اسکن…', 'status.reconnecting': 'در حال اتصال مجدد…',
  'status.error': 'خطای اتصال',
  'connection.ready': '', 'connection.connected': '', 'connection.connecting': 'در حال آماده‌سازی مسیر امن.',
  'connection.disconnecting': 'در حال بازیابی شبکه.', 'connection.reconnecting': 'در حال بازیابی مسیر امن.',
  'connection.error': 'Aethon نیاز به بررسی دارد.',

  // سنجش صفحه خانه
  'traffic.upload': 'ارسالی', 'traffic.download': 'دریافتی', 'traffic.ping': 'پینگ',
  'traffic.time': 'زمان', 'traffic.location': 'موقعیت',
  'traffic.locationUnavailable': 'موقعیت در دسترس نیست', 'traffic.locationDetecting': 'در حال تشخیص…',

  // انتخاب کشور سایفون در خانه
  'location.auto': 'خودکار', 'location.autoFull': 'خودکار',
  'location.manual': 'دستی',

  // پیکربندی‌ها — تنظیمات اصلی
  'config.mode': 'حالت اتصال', 'config.vpn': 'VPN دستگاه', 'config.proxy': 'پروکسی SOCKS5',
  'config.protocol': 'پروتکل',
  'config.transport': 'روش اتصال MASQUE', 'config.transportH3': 'HTTP/3 (QUIC)', 'config.transportH2': 'HTTP/2 (TCP)',
  'config.goolMode': 'توپولوژی gool', 'config.goolModeMasque': 'حمل‌شده با MASQUE', 'config.goolModeClassic': 'کلاسیک (WARP در WARP)',
  'config.scan': 'حالت اسکن',
  'config.psiphonTransport': 'انتقال سایفون', 'config.psiphonTransportAuto': 'خودکار', 'config.psiphonTransportDirect': 'مستقیم',
  'config.psiphonLocation': 'موقعیت سایفون', 'config.psiphonAuto': 'خودکار',
  'config.endpoint': 'نقطه پایانی سفارشی',
  'config.reconnect': 'اتصال مجدد خودکار', 'config.reconnectHelp': 'بازیابی پس از خطاهای موقت',
  'config.lan': 'اتصال از شبکه محلی', 'config.lanHelp': 'اجازه به دستگاه‌های شبکه خصوصی مورد اعتماد برای استفاده از Aethon',
  'config.dns': 'مسیریابی خصوصی DNS', 'config.dnsHelp': 'مسیریابی DNS از Aethon',
  'config.kill': 'مسدودسازی هنگام خطا', 'config.killHelp': 'مسدود کردن ترافیک هنگام بازیابی',
  'config.mtu': 'MTU شبکه', 'config.mtuHelp': 'اندازه بسته‌های آداپتور Aethon (به‌طور خودکار مطابق پروتکل محدود می‌شود)',
  'config.lanDisabled': 'اشتراک‌گذاری LAN غیرفعال است', 'config.lanUnavailable': 'وضعیت LAN در دسترس نیست',

  // گزینه‌های حالت اسکن
  'scan.turbo': 'توربو', 'scan.balanced': 'متعادل', 'scan.thorough': 'کامل',
  'scan.stealth': 'تأییدشده', 'scan.ironclad': 'آهنین',

  // تنظیمات بیشتر — عناوین بخش‌ها
  'more.performance': 'کارایی', 'more.privacy': 'حریم خصوصی و امنیت', 'more.network': 'شبکه و مسیریابی',
  'more.proxy': 'پروکسی و زنجیره', 'more.protocols': 'پروتکل‌های پیشرفته',
  'more.organization': 'سازمان / اعتماد صفر', 'more.help': 'راهنما',

  // کارایی
  'config.ech': 'ECH (رمزنگاری Client Hello)', 'config.echOff': 'خاموش', 'config.echAuto': 'خودکار', 'config.echCustom': 'سفارشی (Base64)', 'config.echCustomLabel': 'پیکربندی ECH (Base64)',
  'config.h2Fragment': 'قطعه‌سازی ClientHello (HTTP/2)', 'config.h2FragmentHelp': 'تقسیم ClientHello برای دور زدن DPI در h2',
  'config.h2FragmentSize': 'اندازه قطعه (بایت)', 'config.h2FragmentDelay': 'تأخیر قطعه (میلی‌ثانیه)',
  'config.noDataCheck': 'رد اعتبارسنجی داده', 'config.noDataCheckHelp': 'غیرفعال کردن بررسی پایان به پایان (سریع‌تر، کمتر قابل اعتماد)',
  'config.validateSecs': 'مهلت اعتبارسنجی (ثانیه)', 'config.startupSecs': 'مهلت راه‌اندازی (ثانیه)', 'config.reconnectSecs': 'تأخیر اتصال مجدد (ثانیه)',

  // شبکه و مسیریابی
  'config.ipv6': 'رفتار IPv6', 'config.ipv6Tunnel': 'تونل', 'config.ipv6Block': 'مسدود',
  'config.routeSniff': 'شناخت SNI/Host مسیریابی', 'config.routeSniffHelp': 'خواندن نام سرور از بایت‌های اول برای قوانین مسیریابی',
  'config.routeSniffMs': 'مهلت شناخت (میلی‌ثانیه)',

  // پروکسی و زنجیره
  'config.upstreamProxy': 'پروکسی بالادستی', 'config.upstreamProxyHelp': 'زنجیره کردن Aether پشت پروکسی دیگر (با پروتکل‌های + سایفون یا + تور در دسترس نیست)',

  // پروتکل‌های پیشرفته
  'config.wiwEndpoints': 'نقطه‌های پایانی WARP-in-WARP (gool کلاسیک)', 'config.wiwEndpointsHelp': 'هاپ‌های اختیاری بیرونی/درونی؛ تعیین هرکدام توپولوژی کلاسیک gool را انتخاب می‌کند',
  'config.mimOuter': 'نقطه پایانی بیرونی MIM', 'config.mimInner': 'نقطه پایانی درونی MIM',
  'config.quicV2': 'آغازگر QUIC v2', 'config.quicV2Help': 'اجازه استفاده هسته از آغازگر مذاکره v2 برای MASQUE',
  'config.wgNoProfileRetry': 'WireGuard: بدون تلاش مجدد پروفایل', 'config.wgNoProfileRetryHelp': 'در صورت شکست اسکن، پروفایل‌های دیگر امتحان نشوند',

  // سازمان
  'config.zeroTrust': 'اعتماد صفر (تیم)',
  'config.team': 'نام تیم', 'config.accessEmail': 'ایمیل دسترسی', 'config.accessToken': 'توکن دسترسی',
  'config.gateway': 'استفاده از پروکسی گیت‌وِی', 'config.gatewayHelp': 'مسیریابی HTTP/HTTPS از طریق گیت‌وِی سازمان',

  // راهنما (هم‌ارز محتوایی Android dev.020)
  'help.mode': 'حالت اتصال',
  'help.mode.body': 'VPN دستگاه تمام ویندوز را به‌طور خودکار از Aethon عبور می‌دهد — مرورگر، برنامه‌ها و DNS. حالت پروکسی SOCKS5 در عوض پورت‌های محلی (HTTP 127.0.0.1:1818 و SOCKS5 127.0.0.1:1819) را در اختیار می‌گذارد که خودتان برنامه‌ها را به آن‌ها معرفی می‌کنید. پروتکل انتخابی شما هنگام تغییر حالت حفظ می‌شود.',
  'help.protocol': 'پروتکل',
  'help.protocol.body': 'اتصال هوشمند سریع‌ترین پروتکل را انتخاب می‌کند. WireGuard و gool و MASQUE انتقال‌های پایه‌اند و MIM دو هاپ MASQUE را زنجیر می‌کند. gool از نسخهٔ v2.3.0 هسته دو توپولوژی دارد (به «توپولوژی gool» مراجعه کنید). مواردی که به «+ سایفون» یا «+ تور» ختم می‌شوند ترافیک شما را پس از تونل پایه از همان زنجیر حریم خصوصی عبور می‌دهند — نیازی به فعال‌سازی چیز دیگری نیست. پیش‌فرض: WireGuard + سایفون.',
  'help.psiphon': '+ سایفون',
  'help.psiphon.body': 'ترافیک از شبکه سایفون خارج می‌شود، پس سایت مقصد به‌جای تونل زیرین، خروجی سایفون را می‌بیند. موقعیت خانه، خروجی واقعی سایفون را نشان می‌دهد. با WireGuard و gool و MASQUE کار می‌کند.',
  'help.tor': '+ تور',
  'help.tor.body': 'ترافیک از شبکه تور خارج می‌شود. موقعیت خانه کشور خروجی واقعی تور را نشان می‌دهد (فقط خواندنی). با WireGuard و gool و MASQUE کار می‌کند. اتصال‌های اول ممکن است به‌دلیل دانلود فهرست تور طولانی‌تر باشند.',
  'help.masqueMethod': 'روش اتصال MASQUE',
  'help.masqueMethod.body': 'نحوه اتصال MASQUE را انتخاب می‌کند: HTTP/3 (QUIC) یا HTTP/2 (TCP). پیش‌فرض HTTP/2 است؛ اگر شبکه یکی را مسدود کند، دیگری معمولاً کار می‌کند. این تنظیم برای MASQUE و MIM اعمال می‌شود و با تغییر پروتکل به یاد سپرده می‌شود.',
  'help.goolMode': 'توپولوژی gool',
  'help.goolMode.body': 'هستهٔ Aether نسخهٔ v2.3.0 در gool را به دو صورت می‌سازد. «حمل‌شده با MASQUE» (پیش‌فرض هسته) تونل WireGuard را درون تونل MASQUE حمل می‌کند که نشانی خروج را تغییر می‌دهد؛ برای این حالت باید شبکه اسکن دروازهٔ MASQUE را عبور دهد. «کلاسیک (WARP در WARP)» WireGuard را درون WireGuard قرار می‌دهد و خروج سادهٔ WARP را نگه می‌دارد؛ روی شبکه‌هایی که اسکن MASQUE را می‌بندند کار می‌کند. اگر gool حمل‌شده با MASQUE دروازه‌ای پیدا نکند، یک اتصال یک‌بار به کلاسیک بازمی‌گردد و توپولوژی واقعاً در حال اجرا در پیام «متصل» اعلام می‌شود — هرگز بی‌صدا عوض نمی‌شود. انتخاب شما همین است که اجرا می‌شود؛ بازگشت فقط وقتی انتخاب شما نتواند روی این شبکه اسکن کند توپولوژی دیگر را برمی‌گزیند و توپولوژی موفق را به یاد می‌سپارد. تعیین نقطهٔ پایانی WARP-in-WARP همیشه کلاسیک را انتخاب می‌کند.',
  'help.scan': 'حالت اسکن',
  'help.scan.body': 'میزان جست‌وجوی Aethon برای یافتن نقطه پایانی سالم. توربو سریع‌ترین و آهنین پایدارترین است روی شبکه‌های به‌شدت فیلترشده و «تأییدشده» (پیش‌تر پنهان) فقط لبه‌هایی را می‌شمارد که پاسخ‌دهی‌شان اندازه‌گیری شده است. پیش‌فرض: متعادل.',
  'help.psiphonTransport': 'انتقال سایفون',
  'help.psiphonTransport.body': 'راه رسیدن کمک‌گزار سایفون به شبکه‌اش درون تونل. خودکار بر اساس شرایط انتخاب می‌کند؛ مستقیم از رله‌های وب اجتناب می‌کند. پیش‌فرض: خودکار.',
  'help.psiphonLocation': 'موقعیت سایفون',
  'help.psiphonLocation.body': 'کشور خروج ترافیک شما را وقتی پروتکل + سایفون انتخاب شده تعیین می‌کند. خودکار به سایفون واگذار می‌شود. انتخابگر خانه و این تنظیم یکی هستند.',
  'help.split': 'تونل تفکیکی',
  'help.split.body': 'حالت «شامل» فقط برنامه‌های انتخاب‌شده را از Aethon عبور می‌دهد؛ حالت «مستثنا» همه به‌جز آن‌ها را. برنامه‌های سیستمی تا زمانی که «نمایش برنامه‌های سیستمی» را روشن نکنید پنهان‌اند؛ انتخاب‌هایشان با پنهان شدن مجدد حفظ می‌شود.',
  'help.mtu': 'MTU شبکه',
  'help.mtu.body': 'اندازه بسته آداپتور Aethon. خود Aethon آن را مطابق توان پروتکل انتخابی محدود می‌کند؛ فقط برای مشکل خاص شبکه تغییرش دهید.',
  'help.ech': 'ECH (رمزنگاری Client Hello)',
  'help.ech.body': 'نام سرور را در دست‌دادن TLS رمزنگاری می‌کند. خودکار در صورت پشتیبانی استفاده می‌کند؛ سفارشی یک پیکربندی Base64 می‌پذیرد.',
  'help.ipv6': 'رفتار IPv6',
  'help.ipv6.body': 'تونل، IPv6 را در کنار IPv4 عبور می‌دهد وقتی هسته پشتیبانی کند؛ مسدود، IPv6 را خاموش نگه می‌دارد تا دور تونل نشت نکند.',
  'help.reset': 'بازنشانی پیش‌فرض‌ها',
  'help.reset.body': 'پیش‌فرض‌های پیکربندی Aethon را بازمی‌گرداند (WireGuard + سایفون، خودکار، خودکار، HTTP/2). زبان، پوسته و زمان اتصال روزانه شما پیکربندی نیستند و حفظ می‌شوند.',

  // گفت‌وگوی بازنشانی
  'reset.title': 'بازنشانی تنظیمات؟', 'reset.body': 'پیش‌فرض‌های پیکربندی Aethon بازگردانده می‌شود.',
  'reset.cancel': 'لغو', 'reset.confirm': 'بازنشانی',

  // تونل تفکیکی
  'split.title': 'تونل تفکیکی', 'split.help': 'انتخاب کنید کدام برنامه‌ها از Aethon استفاده کنند',
  'split.include': 'شامل برنامه‌ها', 'split.exclude': 'مستثنا کردن برنامه‌ها',
  'split.select': 'انتخاب برنامه‌ها', 'split.search': 'جست‌وجوی برنامه‌ها', 'split.add': 'افزودن فایل اجرایی',
  'split.systemApps': 'نمایش برنامه‌های سیستمی',
  'split.missing': 'برنامه حذف‌شده',

  // تنظیمات
  'settings.appearance': 'ظاهر', 'settings.system': 'سیستم', 'settings.light': 'روشن', 'settings.dark': 'تیره',
  'settings.language': 'زبان',
  'settings.notifications': 'اعلان‌ها', 'settings.notificationsHelp': 'مدیریت اعلان‌های VPN و به‌روزرسانی',
  'settings.manageNotifications': 'مدیریت اعلان‌ها',
  'settings.support': 'پشتیبانی', 'settings.supportHelp': 'جزئیات اتصال و وضعیت برای عیب‌یابی',
  'settings.openDiagnostics': 'عیب‌یابی پیشرفته',
  'settings.orbStyle': 'سبک گوی', 'settings.orbClassic': 'کلاسیک', 'settings.orbMercury': 'جیوه زنده',
  'settings.backup': 'پشتیبان‌گیری و بازیابی', 'settings.backupHelp': 'برون‌بری یا بازگردانی پیکربندی Aethon',
  'settings.export': 'برون‌بری پیکربندی', 'settings.import': 'بازگردانی پیکربندی',

  // به‌روزرسانی
  'updates.automatic': 'به‌روزرسانی خودکار', 'updates.automaticHelp': 'بررسی بی‌صدا در پس‌زمینه',
  'updates.current': 'نسخه فعلی', 'updates.notChecked': 'بررسی نشده', 'updates.check': 'بررسی به‌روزرسانی',
  'updates.download': 'دریافت', 'updates.install': 'نصب', 'updates.checking': 'در حال بررسی به‌روزرسانی',
  'updates.upToDate': 'به‌روز است', 'updates.available': 'به‌روزرسانی موجود است', 'updates.ready': 'آماده نصب',
  'updates.failed': 'به‌روزرسانی ناموفق بود',

  // درباره
  'about.title': 'درباره', 'about.original': 'پروژه اصلی: Aether',
  'about.originalCredit': 'بر پایه پروژه اصلی Aether از CluvexStudio.',
  'about.application': 'برنامه: Aethon VPN',
  'about.applicationCredit': 'برنامه و رابط Android/Windows توسعه‌یافته برای Aether.',
  'about.version': 'نسخه', 'about.coreVersion': 'هسته Aether',
  'about.telegram': 'جامعه تلگرام',

  // انتخاب‌گر و پیام‌ها
  'picker.empty': 'برنامه‌ای پیدا نشد', 'picker.added': 'فایل اجرایی افزوده شد', 'picker.selected': 'انتخاب‌شده',
  'toast.updated': 'Aethon به‌روز است', 'toast.telegram': 'تلگرام در دسترس نیست؛ مرورگر باز شد.',
  'toast.backupSaved': 'پیکربندی برون‌بری شد', 'toast.backupLoaded': 'پیکربندی بازیابی شد', 'toast.backupInvalid': 'فایل پشتیبان معتبر نیست',
};

export const translations = { en, fa };
let language = 'en';
export function t(key){const selected=translations[language];if(Object.hasOwn(selected,key))return selected[key];if(Object.hasOwn(translations.en,key))return translations.en[key];return key}
export function setLanguage(next){language=next==='fa'?'fa':'en';document.documentElement.lang=language;document.documentElement.dir=language==='fa'?'rtl':'ltr';document.querySelectorAll('[data-i18n]').forEach(node=>node.textContent=t(node.dataset.i18n));document.querySelectorAll('[data-i18n-placeholder]').forEach(node=>node.placeholder=t(node.dataset.i18nPlaceholder))}
export function applyTranslations(){setLanguage(language)}
export function currentLanguage(){return language}
