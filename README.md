# FIRAT NODE

FIRAT NODE is a true-black, Linux-flavoured Android launcher and private control plane. It was built for a rooted Samsung Galaxy S9+ running Android 10, while keeping Android as the hardware-compatibility layer for the camera, modem, fingerprint sensor and iris scanner.

## Highlights

- OLED-black launcher with pixel shifting and an immersive, gesture-friendly dock
- Text-first app drawer, searchable launcher and built-in help/control screens
- Biometric/device-credential vault for Codex, SSH and Debian actions
- Direct Codex CLI control over Termux + SSH (no API integration in the launcher)
- Tailnet-only Mac and Windows/Lolie status, SSH and SFTP workflows
- Android Calendar Provider agenda with a read-only-by-default launcher view
- Thunderbird notification summaries at 07:30 and every hour
- Local sender/spam filters; the launcher never sends email
- Open-Meteo current weather using coarse device location
- Adaptive 2/3/5-minute Lolie reconnect backoff
- Ten-minute expiry for mail metadata viewed inside the launcher

## Security model

FIRAT NODE contains no SSH keys, passwords, OAuth tokens, Tailnet addresses or host-specific configuration. Private hosts are loaded at runtime from:

```text
/sdcard/Download/firat-node/config.properties
```

Copy `config.properties.example` to that path and edit it locally. Keep remote services bound to Tailscale, use key-only SSH, and do not expose Termux SSH directly to the public internet.

Mail access is metadata-only through Android's notification listener: sender, subject and timestamp. Sending mail or creating calendar events is intentionally outside the automation path and should require explicit user action.

## Build

Requirements: macOS/Linux, JDK 8+ and Android SDK platform 29 with build-tools 35.0.1.

```sh
./build-apk.sh
adb install -r FIRAT-NODE.apk
```

The build uses only Android SDK command-line tools. The generated APK is debug-signed for personal installation and is excluded from Git.

## Companion apps

- [Termux](https://github.com/termux/termux-app)
- [Tailscale](https://github.com/tailscale/tailscale)
- [Thunderbird for Android](https://github.com/thunderbird/thunderbird-android)
- [Fossify Calendar](https://github.com/FossifyOrg/Calendar)
- [Material Files](https://github.com/zhanghai/MaterialFiles)

## Platform note

Android already runs on the Linux kernel. FIRAT NODE uses Android for proprietary S9+ hardware support and a Debian proot userland for Linux tools. Replacing Android completely would usually sacrifice the stock camera, iris support, telephony reliability and other device-specific features.

## License

MIT
