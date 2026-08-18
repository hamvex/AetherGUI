AETHON UPDATE — AETHER CORE v1.7.0 + SPECIFIC UI FIXES

Project:
Aethon VPN

Current Android app version:
Aethon v1.2.1


==================================================
AETHER CORE INFORMATION
==================================================

Main VPN engine:

CluvexStudio/Aether

Official releases:

https://github.com/CluvexStudio/Aether/releases


Current integrated Aether core version in Aethon:

v1.5.0


Target core version:

Aether v1.7.0


IMPORTANT:
Upgrade the VPN engine used by Aethon from Aether v1.5.0 to Aether v1.7.0.


==================================================
CORE INTEGRATION CHECK (REQUIRED)
==================================================

Before making changes:

Inspect the Aethon project and identify exactly how Aether v1.5.0 is integrated.

Find:

- Core location
- Version reference
- Build integration
- Runtime binaries
- Dependencies
- Android/Windows integration


Do not assume the integration method.

First understand the current architecture, then upgrade.


==================================================
PART 1 — AETHER CORE UPGRADE
==================================================

Upgrade only the Aether core integration.

Required:

- Replace Aether v1.5.0 with Aether v1.7.0.
- Update required dependencies only if needed.
- Apply required migration changes.


Preserve:

- Existing UI
- Existing features
- Existing configuration system
- Existing connection flow


Verify compatibility:

- VPN connection
- Disconnect/reconnect
- TUN mode
- Routing
- DNS handling
- MASQUE
- Existing protocols


==================================================
PART 2 — UI CHANGES ONLY
==================================================

Apply ONLY these UI changes.

Do not redesign the application.


1. CONNECTION STATUS

Replace:

Flow Core Ready

Tunnel Active


With:


Disconnected:

Aethon Ready


Connected:

Aethon Active


--------------------------------


2. LOCATION

Remove static location values.

Implement real-time VPN location detection.


After successful VPN connection:

- Detect VPN exit public IP.
- Resolve GeoIP location.
- Display actual VPN location.


States:


Disconnected:

Location unavailable


Connecting:

Detecting location...


Connected:

Example:

🇩🇪 Frankfurt am Main


Rules:

- Use VPN exit IP.
- Do not use user's real IP.
- No server selection.
- No change button.
- Display only.


--------------------------------


3. PING

Replace:

LATENCY


With:

Ping


Requirements:

- Ping value must be bold.
- Keep current placement.
- Do not redesign the section.


--------------------------------


4. TRAFFIC

Replace:

SESSION


With:

Traffic


Do not change the design.

Only replace the text label.


--------------------------------


5. SETTINGS

Remove:

About


Reason:

About already exists as a separate section.


Do not modify other settings.


==================================================
BUILD AND VALIDATION
==================================================

After completing changes:

Build:

- Android release APK
- Windows production build


Verify:

- Aether core updated to v1.7.0.
- Android version remains v1.2.1.
- Application launches.
- VPN connection works.
- Existing features remain functional.
- Only requested UI changes are applied.


Final report:

1. Previous Aether version: v1.5.0
2. New Aether version: v1.7.0
3. Core integration changes
4. Files modified
5. Android build result
6. Windows build result
7. Any remaining issues


Do not make any changes outside this specification.