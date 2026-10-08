# Third-party notices

Aethon is an independent graphical frontend maintained by hamvex.

This application bundles the Aether executable from https://github.com/CluvexStudio/Aether.
Aether is licensed under GNU AGPL v3.0. Windows and Android use official v2.3.0 executables. Each platform has separate repository-pinned archive and payload SHA-256 digests. Aether and its marks are subject to the upstream project's TRADEMARK.md policy; Firstham AetherGui is an independent frontend and is not endorsed by CluvexStudio.

System-wide VPN Mode bundles Xray v26.3.27 from https://github.com/XTLS/Xray-core as the TUN and SOCKS5 routing engine. Xray is licensed under MPL-2.0. The unmodified official Windows archive is pinned to SHA-256 `d004c39288ce9ada487c6f398c7c545f7d749e44bdfdd59dbc9f865afba4e1ad`; the extracted `xray.exe` is pinned separately to SHA-256 `15c2d007954ac53ba69b80ec91242786b3c0b71d52649165b4ca1d5cc96ef8f1`. The license is distributed at `third-party/xray-LICENSE.txt`. Update the version, digests, generated-configuration tests, and this notice together.

The sing-box license is retained for historical reference only. sing-box is not included in current Aethon releases.

The Android application bundles official Aether v2.3.0 Android cores for ARMv7, ARM64, and x86_64, verified against the GitHub release asset digests recorded in `scripts/aether-pins.json`. Android VPN routing uses HEV Socks5 Tunnel v2.16.0 from https://github.com/heiher/hev-socks5-tunnel under the MIT license. HEV source pins and per-build hashes are recorded in pin metadata and build manifests; its license is in `third-party/hev-socks5-tunnel-LICENSE.txt`.

Android Phase 2 selects Aether v2.3.0's official Psiphon companion from
https://github.com/CluvexStudio/psiphon-tunnel-core at commit
`83aa73b9b982e7421e00117f5b0c5aceb5dda452`. The hash-anchored input manifest
`work/phase1-20260927/PHASE_1_NATIVE_INPUTS.json` records the selected per-ABI
payloads. Lyrebird is a separate Tor pluggable-transport helper from
https://gitlab.torproject.org/tpo/anti-censorship/pluggable-transports/lyrebird
at commit `fc105a03c0e0acc2479301c361c012ffed359c43`. These are verified build
inputs, not evidence of a new packaged or runtime-approved release. Before
distribution, include the licenses and corresponding-source obligations for
the actual selected helper revisions and their dependencies.

The retired standalone Windows Psiphon experiment used Psiphon-Labs commit
`38148cd835e07d688dbb6b30ae24ad2fd0e5d847` under GPL-3.0. Its license text
at `third-party/psiphon-LICENSE.txt` and historical reports are retained as
historical provenance only; they do not describe the current Android helper.
The duplicate manager is removed and its fetch/build entrypoint is disabled.
