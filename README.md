# AtticusVPN

**Turn your Android device into a self-hosted WireGuard VPN server.**

AtticusVPN Android Server 0.6.1 is available as source and an official signed APK using the same signing certificate as 0.6.0.

## Download AtticusVPN

**Latest stable version: v0.6.1**

[![Download APK](https://img.shields.io/badge/Download-APK-green?logo=android)](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.1/AtticusVPN-Server-v0.6.1.apk)

[View Release](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.6.1) · [Source Code](https://github.com/zeejoy/AtticusVPN/tree/v.0.6.1) · [Source ZIP](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.1/AtticusVPN-Android-Server-v0.6.1-source.zip) · [SHA-256 checksums](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.1/SHA256SUMS-v0.6.1.txt)

Requires **Android 7.0+ (API 24+)**. Remote access also requires public IPv6 and permitted inbound UDP; see the requirements and verification limits below.

## Features and requirements

- Standard WireGuard protocol, Peer management, client configuration and QR export.
- Public IPv6 UDP server endpoint with userspace IPv4 TCP/UDP and DNS forwarding; ordinary operation does not require root.
- Dynamic DNS, encrypted local credential/state storage, runtime diagnostics, and background-permission guidance.
- Thirteen interface languages and light, dark, or system appearance.
- Android 7.0 or later (API 24+). Remote access requires a usable public IPv6 address, inbound UDP permitted by the network, and an IPv6-capable client network.

DDNS success and the phone's own Internet connectivity do not prove remote VPN reachability. This is not a general IP router: ICMP/arbitrary IP forwarding and full IPv6 Internet tunneling are not supported or guaranteed. OEM background behavior, hardware Keystore recovery, sustained load, and carrier/public-network compatibility still require device testing. STABLE 0.6.0 does not implement an IKEv2/IPsec server or Atticus Link.

## Changes in 0.6.1

The app displays `0.6.1` (version code `21`). Network-check results appear beside the home screen’s traffic counters. The Device Capabilities page and the Seal export hint were removed.

## Changes in 0.6.0

0.6.0 adds a redesigned home/settings interface, runtime logs, 13-language UI, and background-access guidance. It includes fixes for recoverable encrypted-state backups, global/per-Peer TCP quotas and idle cleanup, TCP half-close responses, DNS response/question matching, foreground notification updates, safe gateway diagnostics, build scripts, source protection, and privacy documentation.

The 0.6.0 production build used application ID `dev.lan.atticusvpn.server`, version code `20`, and version name `0.6.0-stable`. The completed device check covers a controlled 0.5.8 installation with a test Peer; it does not establish preservation of every existing production configuration.

## Source, build, and installation

The implementation remains in [AtticusVPN-Android-Server](./AtticusVPN-Android-Server/): `app/`, Java `core/`, Go `native-engine/`, tests, artwork, and documentation. Existing repository history is retained.

Use JDK 17, Go 1.27.3, PowerShell 7, Android SDK Platform 36 / Build Tools 36.0.0, and NDK 29.0.14206865. The repository pins Gradle 9.3.1 and AGP 9.1.0. Build the native AAR before building the app; it is intentionally excluded from the source tree. Initial builds require dependency downloads.

See the [Android Server README](./AtticusVPN-Android-Server/README.md), [native build instructions](./AtticusVPN-Android-Server/native-engine/README.md), and [usage guide](./AtticusVPN-Android-Server/docs/USAGE.md). Developer-built Debug packages use a separate application ID and cannot validate an upgrade of the production app. A production upgrade requires the original signing key and retained application data; never uninstall or clear data to simulate upgrade success.

## Downloads and verification

The [v.0.6.1 release page](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.6.1) provides the signed APK, source archives, and checksum file. APK SHA-256: `9f32cd22bebe98b556d31d6ee19a3dae6597fc7289c2cf884d1a8f7707c65488`.

### 0.6.0 verification history

The [v.0.6.0 release page](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.6.0) provides the [official signed APK](https://github.com/zeejoy/AtticusVPN/releases/download/v.0.6.0/AtticusVPN-Server-v0.6.0.apk), source archives, checksums, third-party corresponding source, and build/license instructions. APK SHA-256: `074bc9e90bbdf0f4c40f365788b805c623625ff2ff50aef6ac9f3d6888dcc0cd`. Certificate SHA-256: `2ff3b123dbaa5350542e1ee3c2cc371dde487038db3553a35d5754126caeab32` (same as 0.5.8). The APK is not debuggable and verifies with APK Signature Schemes v2/v3. The source tag and its original archives remain unchanged. The dated [source release validation](./AtticusVPN-Android-Server/docs/SOURCE-RELEASE-0.6.0.md) records the source-only publication stage; the Release notes record the later signing and limited device upgrade test. Earlier dated audit reports are historical evidence, not a claim that those checks ran again.

An MI MAX 3 Android 10 in-place upgrade retained the application UID, first-install timestamp and a newly created test Peer's name, addresses, enabled state and export availability. Existing production configuration retention, server identity continuity, actual DDNS settings, WireGuard handshake/Internet forwarding, service start/stop, sustained locked-screen operation, other Android devices and public IPv6/carrier compatibility remain unverified. See the Release notes for the exact test scope. Do not treat a locally built Debug APK or unsigned Release APK as an official upgrade.

## Historical version 0.5.8

The [0.5.8 release and its APK](https://github.com/zeejoy/AtticusVPN/releases/tag/v.0.5.8) remain available for historical reference. It has the disclosed **F01–F04** reliability issues affecting state recovery, TCP resource limits, TCP half-close handling and DNS response matching. It is not recommended for critical uses or long-term operation.

## Security and privacy

Read the [Security Policy](./SECURITY.md) before reporting a vulnerability. Do not publish keys, credentials, logs, or sensitive vulnerability details. See the [Privacy Policy](./AtticusVPN-Android-Server/PRIVACY.md) for local runtime logs and user-controlled export behavior.

The fixed `golang.org/x/crypto` version retains an OpenPGP module-level advisory. The unused OpenPGP packages must remain outside the dependency graph; a repository check guards that boundary. This is not a claim that all vulnerabilities have been eliminated.

## License

Project-owned code and materials use [Apache-2.0](./LICENSE). Third-party components retain their respective licenses, including MIT, BSD, GPLv2 with applicable exceptions, and other notices. Read the [third-party notices](./AtticusVPN-Android-Server/THIRD_PARTY_NOTICES.md), [NOTICE](./AtticusVPN-Android-Server/NOTICE), and [desugar corresponding-source instructions](./AtticusVPN-Android-Server/docs/CORRESPONDING-SOURCE-STABLE-0.6.0.md). The project's Apache license does not relicense those components.
