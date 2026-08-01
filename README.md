# DAAK NODE

DAAK NODE is a true-black, Linux-flavoured Android launcher and private control plane. It was built for a rooted Samsung Galaxy S9+ running Android 10, while keeping Android as the hardware-compatibility layer for the camera, modem, fingerprint sensor and iris scanner.

## Highlights

- OLED-black launcher with pixel shifting and an immersive, gesture-friendly dock
- Dedicated three-column landscape control plane and adaptive landscape app/help/disk screens
- Text-first app drawer, searchable launcher and built-in help/control screens
- Biometric/device-credential vault for Codex, SSH and Debian actions
- Direct Codex CLI control over Termux + SSH (no API integration in the launcher)
- Tailnet-only Mac and Windows/Lolie status, SSH and navigable B-drive workflows
- daakLOLILE private dashboard integration
- Four-device RustDesk touch hub with locally configurable IDs or Tailnet endpoints (default direct-access port 21118)
- Android Calendar Provider agenda with a read-only-by-default launcher view
- Thunderbird notification summaries at 07:30 and every hour
- Local sender/spam filters; the launcher never sends email
- Open-Meteo current weather using coarse device location
- Adaptive 2/3/5-minute Lolie reconnect backoff
- Ten-minute expiry for mail metadata viewed inside the launcher
- Native Tailnet integration with daakREMEMBER for reading and quick-capturing notes
- Opt-in, keyword-limited WhatsApp notification-to-task capture; never sends messages
- On-device Turkish dictation through Android's speech-recognition contract (tested with FUTO Voice Input)
- DAAK Inbox routing: review a Codex CLI suggestion before writing dictated text to daakREMEMBER, Obsidian, both, or a Codex session
- Four user-configurable pinned application slots
- One-way daakREMEMBER task export into `DAAK-Vault/daakREMEMBER.md` for Obsidian
- Obsidian deep link into a local `DAAK-Vault`
- Right-edge launcher gesture into the control centre
- Optional daily update checks with a pinned manifest and mandatory APK SHA-256 verification

## Security model

DAAK NODE contains no SSH keys, passwords, OAuth tokens, Tailnet addresses or host-specific configuration. Private hosts are loaded at runtime from:

```text
/sdcard/Download/daak-node/config.properties
```

Copy `config.properties.example` to that path and edit it locally. Keep remote services bound to Tailscale, use key-only SSH, and do not expose Termux SSH directly to the public internet.

Mail access is metadata-only through Android's notification listener: sender, subject and timestamp. WhatsApp processing is limited to notification text that Android has already decrypted and displayed; only explicit task-like phrases are captured. DAAK NODE never sends mail or WhatsApp messages.

daakREMEMBER traffic uses its existing HTTP snapshot/merge protocol on TCP 45831. The companion Mac service rejects non-Tailnet source addresses; Tailscale supplies the encrypted transport. DAAK NODE does not expose a new listening port.

Remote desktop targets are kept in Android private preferences and handed to RustDesk without embedding passwords. For direct-IP mode, keep TCP 21118 limited to the Tailnet at the host firewall; Tailscale provides the encrypted transport. DAAK Inbox does not write a dictated item anywhere until the user selects a destination in its confirmation dialog.

The Android package name remains `com.firat.node` so upgrades preserve launcher state, permissions and private preferences. The old `/sdcard/Download/firat-node/config.properties` path remains a read-only compatibility fallback.

## Build

Requirements: macOS/Linux, JDK 8+ and Android SDK platform 29 with build-tools 35.0.1.

```sh
./build-apk.sh
adb install -r DAAK-NODE.apk
```

The build uses only Android SDK command-line tools. The generated APK is debug-signed for personal installation and is excluded from Git.

## Companion apps

- [Termux](https://github.com/termux/termux-app)
- [Tailscale](https://github.com/tailscale/tailscale)
- [Thunderbird for Android](https://github.com/thunderbird/thunderbird-android)
- [Fossify Calendar](https://github.com/FossifyOrg/Calendar)
- [Material Files](https://github.com/zhanghai/MaterialFiles)
- [daakREMEMBER](https://github.com/benfirad/daakREMEMBER)
- [Obsidian](https://github.com/obsidianmd/obsidian-releases)
- [FUTO Voice Input](https://github.com/futo-org/voice-input)
- [RustDesk](https://github.com/rustdesk/rustdesk)

## Platform note

Android already runs on the Linux kernel. DAAK NODE uses Android for proprietary S9+ hardware support and a Debian proot userland for Linux tools. Replacing Android completely would usually sacrifice the stock camera, iris support, telephony reliability and other device-specific features.

## License

MIT
