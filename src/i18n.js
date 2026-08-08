export const translations = {
  en: {
    'nav.main':'Main navigation','nav.connection':'Connection','nav.diagnostics':'Diagnostics','nav.guide':'User guide','nav.about':'About','nav.tour':'Welcome tour',
    'language.label':'Language','language.ltr':'Left to right','language.rtl':'Right to left','dashboard.title':'Connection','dashboard.subtitle':'Start Aether and use its local proxy in your applications.',
    'status.disconnected':'Disconnected','status.scanning':'Scanning','status.connecting':'Connecting','status.connected':'Connected','status.reconnecting':'Reconnecting','status.error':'Error',
    'connection.label':'AETHER CONNECTION','connection.readyTitle':'Disconnected','connection.readyMessage':'Recommended settings are selected.','connection.scanningTitle':'Finding an endpoint…','connection.scanningMessage':'Aether is testing available endpoints.','connection.connectingTitle':'Establishing the tunnel…','connection.connectingMessage':'A healthy endpoint was found.','connection.connectedTitle':'Proxy is ready','connection.connectedMessage':'Applications can now use the local SOCKS5 proxy.','connection.reconnectingTitle':'Restoring connection…','connection.reconnectingMessage':'Aether is trying the last verified route.','connection.errorTitle':'Connection needs attention','connection.errorMessage':'Review Diagnostics, then try another profile.',
    'actions.connect':'Connect','actions.disconnect':'Disconnect','actions.verify':'Verify','actions.verifyConnection':'Verify connection','actions.copy':'Copy','actions.reset':'Reset defaults','actions.copyLogs':'Copy logs','actions.clear':'Clear','actions.close':'Close','actions.back':'Back','actions.next':'Next','actions.start':'Start connecting',
    'facts.endpoint':'Endpoint','facts.proxy':'SOCKS5 proxy','facts.elapsed':'Elapsed','facts.notSelected':'Not selected',
    'proxy.title':'Local proxy','proxy.description':'Use these SOCKS5 details in v2rayN, Proxifier, or another application.',
    'settings.title':'Connection profile','settings.subtitle':'Three choices are usually all you need.','settings.protocol':'Protocol','settings.scan':'Scan mode','settings.transport':'MASQUE transport','settings.recommendedHint':'MASQUE, Balanced, and HTTP/3 are recommended for most users.','settings.ipMode':'IP scan','settings.obfuscation':'Obfuscation','settings.socks':'SOCKS5 listen address','settings.peer':'Custom endpoint','settings.allowRemote':'Allow a non-local listener','settings.keepalive':'WireGuard keepalive','settings.timeout':'Stall timeout','settings.watchdog':'Watchdog','settings.quickReconnect':'Quick reconnect','settings.baseConfig':'Base configuration','settings.wgConfig':'WireGuard configuration','settings.masqueConfig':'MASQUE configuration','settings.dns':'Tunnel DNS resolvers','settings.routesFile':'Routing rules file','settings.routeBlock':'Block destinations','settings.routeDirect':'Direct destinations',
    'recommended.masque':'Recommended: MASQUE','recommended.gool':'Default: gool / WARP-in-WARP','common.recommended':'Recommended','advanced.title':'Advanced Settings','advanced.subtitle':'Network details, safety, and configuration files','advanced.network':'Network','advanced.reliability':'Reliability and safety','advanced.files':'Configuration files',
    'tooltip.protocol':'The tunnel technology Aether uses. MASQUE is the best starting point.','tooltip.scan':'Controls how quickly and thoroughly Aether searches for an endpoint.','tooltip.transport':'HTTP/3 uses UDP/QUIC. HTTP/2 uses TCP and can work when UDP is blocked.','tooltip.ipMode':'Selects the IP address families included in scanning.','tooltip.obfuscation':'Changes traffic patterns to improve reliability on filtered networks.','tooltip.socks':'The local address other applications use to reach Aether.','tooltip.peer':'Skips automatic discovery and uses a specific IP address and port.','tooltip.keepalive':'Seconds between WireGuard keepalive packets.','tooltip.timeout':'How long the GUI waits before treating a connection attempt as stuck.',
    'help.ipMode':'IPv4 is the safest default.','help.socks':'Keep this on localhost for normal use.','help.peer':'Recommended: leave empty.','help.allowRemote':'This can expose the proxy to other devices.','help.watchdog':'Stop a core process that becomes stuck.','help.quickReconnect':'Try the last verified endpoint first.','help.configFiles':'The GUI stores file paths, never keys or credentials.','help.obfuscationMasque':'Firewall is recommended for MASQUE.','help.obfuscationOther':'Balanced is recommended for this protocol.','help.dns':'Comma-separated IP addresses used inside the tunnel.','help.routesFile':'Optional file containing [block] and [direct] rules.',
    'placeholder.peer':'Leave empty for automatic scan','placeholder.defaultPath':'Use application default','placeholder.optional':'Optional','unit.seconds':'seconds',
    'diagnostics.title':'Diagnostics','diagnostics.subtitle':"Verify the proxy or inspect Aether's live output.",'diagnostics.verifyTitle':'Connection check','diagnostics.verifyDescription':'Send an HTTPS request through the configured SOCKS5 proxy.','diagnostics.result':'Cloudflare trace result','diagnostics.logs':'Live logs','diagnostics.logsHelp':'Useful when a connection fails or stalls.','diagnostics.waiting':'Waiting for Aether…',
    'docs.title':'User guide','docs.subtitle':'Setup, concepts, proxy guides, and troubleshooting.','docs.search':'Search documentation','docs.sections':'Documentation sections','docs.noResults':'No matching sections found.','docs.contents':'Contents',
    'about.title':'About','about.subtitle':'Application details and project links.','about.description':'A compact Windows frontend for the official Aether networking core.','about.appVersion':'Application','about.coreVersion':'Aether core','about.platform':'Platform','about.basedOn':'Based on CluvexStudio/Aether','about.attribution':'The original Aether project remains the networking engine. This GUI is an additional frontend and does not replace the CLI.','about.viewUpstream':'View upstream project','about.telegram':'Join Telegram Channel','about.releases':'Check for updates',
    'wizard.welcomeLabel':'WELCOME','wizard.welcomeTitle':'Welcome to Firstham AetherGui','wizard.welcomeText':'A simple interface for Aether. Choose your language to begin.','wizard.basicsLabel':'THE BASICS','wizard.basicsTitle':'Aether creates a local proxy','wizard.basicsText':'Applications send traffic to Aether through SOCKS5, then Aether carries it through the selected tunnel.','wizard.apps':'Your apps','wizard.protocolLabel':'PROTOCOL','wizard.protocolTitle':'Start with MASQUE','wizard.protocolText':'MASQUE with HTTP/3 is recommended. Switch to HTTP/2 if UDP is blocked or unreliable.','wizard.scanLabel':'SCAN MODE','wizard.scanTitle':'Balanced is the best default','wizard.scanText':'Balanced combines quick discovery with dependable coverage. Try Thorough only if it cannot find an endpoint.','wizard.readyLabel':'READY','wizard.readyTitle':'Connect, then verify','wizard.readyText':'Press Connect, wait for the green Connected state, then verify the proxy from Diagnostics.',
    'scan.turbo':'Turbo — fastest','scan.balanced':'Balanced — recommended','scan.thorough':'Thorough — wider search','scan.stealth':'Stealth — conservative','ip.v4':'IPv4 — recommended','ip.v6':'IPv6 only','ip.both':'IPv4 + IPv6','obfuscation.firewall':'Firewall — recommended','obfuscation.gfw':'GFW','obfuscation.balanced':'Balanced — recommended','obfuscation.aggressive':'Aggressive','obfuscation.light':'Light','obfuscation.off':'Off',
    'vpn.title':'System-wide VPN Mode','vpn.description':'Route Windows traffic automatically through Aether.','vpn.mode':'VPN Mode','vpn.manualMode':'Manual SOCKS5','vpn.routingMode':'Routing','vpn.publicIp':'Public IP','vpn.traffic':'Traffic','vpn.advanced':'System-wide routing','vpn.dnsLeak':'DNS leak protection','vpn.dnsHelp':'Intercept DNS and resolve it through Aether.','vpn.killSwitch':'Kill Switch','vpn.killHelp':'Keep TUN routing closed if the Aether SOCKS5 service fails.','vpn.ipv6':'IPv6 behavior','vpn.mtu':'TUN MTU','vpn.splitApps':'Split-tunnel applications','vpn.splitPlaceholder':'One absolute .exe path per line','vpn.splitHelp':'Used only by Include or Exclude selected applications.','vpn.exclusions':'Additional route exclusions','routing.full':'All traffic','routing.bypassLocal':'Bypass local networks','routing.splitInclude':'Include selected applications','routing.splitExclude':'Exclude selected applications','ipv6.tunnel':'Route IPv6 through Aether','ipv6.block':'Block IPv6 while connected','diagnostics.repair':'Repair network','diagnostics.selfTest':'Run self-test',
    'toast.defaults':'Recommended defaults restored.','toast.proxyCopied':'Proxy address copied.','toast.logsCopied':'Logs copied.','toast.verified':'Connection verified through Aether.','toast.languageSaved':'Language saved.','toast.error':'Something went wrong.','toast.invalidAddress':'Enter a valid IP address and port.','toast.portInUse':'The SOCKS5 port may already be in use.','toast.connectionFailed':'Connection failed. Review Diagnostics and try another profile.'
  }
};

Object.assign(translations.en, {
  'recovery.prompt':'An unfinished VPN session was detected. Repair Windows networking now?',
  'recovery.done':'Network recovery completed.',
  'vpn.addApps':'Add applications',
  'routing.disabled':'Disabled',
  'routing.preparing':'Preparing',
  'routing.requesting-elevation':'Requesting administrator permission',
  'routing.starting-adapter':'Starting virtual adapter',
  'routing.configuring-routes':'Configuring routes',
  'routing.connected':'Connected',
  'routing.reconnecting':'Reconnecting',
  'routing.restoring':'Restoring network settings',
  'routing.error':'Error',
  'settings.logLevel':'Log level',
  'scan.ironclad':'Ironclad — verified tunnel',
  'updates.title':'App Updates',
  'updates.current':'Current version',
  'updates.latest':'Latest available version',
  'updates.status':'Update status',
  'updates.upToDate':'Up to date',
  'updates.available':'Update available',
  'updates.checking':'Checking for updates',
  'updates.downloading':'Downloading update',
  'updates.ready':'Ready to install',
  'updates.installing':'Installing update',
  'updates.failed':'Update failed',
  'updates.check':'Check for Updates',
  'updates.download':'Download update',
  'updates.install':'Install update',
  'updates.automatic':'Automatically download updates',
  'updates.automaticHelp':'Download verified Windows updates when Aethon is running.',
  'updates.releaseNotes':'Release notes',
  'updates.noNotes':'No release notes are available.'
});

export function t(key) {
  return translations.en[key] ?? key;
}
export function applyTranslations(root = document) {
  document.documentElement.lang = 'en';
  document.documentElement.dir = 'ltr';
  root.querySelectorAll('[data-i18n]').forEach(element => { element.textContent = t(element.dataset.i18n); });
  root.querySelectorAll('[data-i18n-placeholder]').forEach(element => { element.placeholder = t(element.dataset.i18nPlaceholder); });
  root.querySelectorAll('[data-i18n-tooltip]').forEach(element => { const value = t(element.dataset.i18nTooltip); element.dataset.tooltip = value; element.title = value; element.setAttribute('aria-label', value); });
  root.querySelectorAll('[data-i18n-aria]').forEach(element => { element.setAttribute('aria-label', t(element.dataset.i18nAria)); });
}
