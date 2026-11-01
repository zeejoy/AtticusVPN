# AtticusVPN

**Turn your Android device into a WireGuard VPN server.**

AtticusVPN is an open-source VPN project focused on running a WireGuard-based VPN server directly on Android devices.

The project aims to make self-hosted VPN services more accessible without requiring a traditional dedicated server.

## Features

- Android-based WireGuard VPN server
- VPN peer management
- IPv6 networking support
- Dynamic DNS (DDNS) integration
- Client configuration and QR code export
- Encrypted credential storage
- English, Simplified Chinese and Traditional Chinese interfaces

## Project Status

**Current Android Server version: 0.5.8**

AtticusVPN is under active development. Some security, compatibility and performance improvements are still in progress.

The current repository release focuses on the Android VPN server. Windows components are not included in this release.

## Source Code

The Android server implementation is located in:

[AtticusVPN-Android-Server](./AtticusVPN-Android-Server/)

It includes:

- `app/` — Android application and user interface
- `core/` — Core Java components
- `native-engine/` — Native networking engine
- `docs/` — Usage and verification documentation

## Building and Usage

Please refer to the following documentation:

- [Android Server README](./AtticusVPN-Android-Server/README.md)
- [Usage Guide](./AtticusVPN-Android-Server/docs/USAGE.md)
- [Native Engine](./AtticusVPN-Android-Server/native-engine/README.md)

## Security

Please read the [Security Policy](./SECURITY.md) before reporting a vulnerability.

Do not publicly disclose private keys, credentials or sensitive vulnerability details.

## Privacy

See the [Privacy Policy](./AtticusVPN-Android-Server/PRIVACY.md).

## License

AtticusVPN is licensed under the [Apache License 2.0](./LICENSE).

Third-party components are subject to their respective licenses. See the [Third-Party Notices](./AtticusVPN-Android-Server/THIRD_PARTY_NOTICES.md).