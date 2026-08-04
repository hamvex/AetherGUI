# Third-party notices

Firstham AetherGui is an independent graphical frontend maintained by hamvex.

This application bundles the Aether executable from https://github.com/CluvexStudio/Aether.
Aether is licensed under GNU AGPL v3.0. The bundled executable is downloaded from the official v1.5.0 release and verified against the publisher-provided SHA-256 checksum during reproducible builds. Aether and its marks are subject to the upstream project's TRADEMARK.md policy; Firstham AetherGui is an independent frontend and is not endorsed by CluvexStudio.

System-wide VPN Mode bundles sing-box v1.13.14 from https://github.com/SagerNet/sing-box as the TUN and SOCKS5 routing engine. sing-box is licensed under GPL-3.0-or-later. Its unmodified official Windows archive is pinned to SHA-256 `f580782c6dd10f7691c66cea1d7c421813c5fbf7e305d1ee7ce0c3a40d196341`; the license is distributed at `third-party/sing-box-LICENSE.txt`. Update the version, digest, generated-configuration tests, and this notice together.

The Android application bundles official Aether v1.5.0 Android cores for ARM64 and x86_64, verified against upstream SHA-256 files. Android VPN routing uses HEV Socks5 Tunnel v2.16.0 from https://github.com/heiher/hev-socks5-tunnel under the MIT license. The pinned native-library hashes and license are maintained in `scripts/fetch-android-assets.ps1` and `third-party/hev-socks5-tunnel-LICENSE.txt`.
