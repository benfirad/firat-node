# DAAK NODE

DAAK NODE is a true-black, Linux-flavoured Android launcher and private control plane. It was built for a rooted Samsung Galaxy S9+ running Android 10, while keeping Android as the hardware-compatibility layer for the camera, modem, fingerprint sensor and iris scanner.

## Highlights

- OLED-black launcher with pixel shifting and an immersive, gesture-friendly dock
- Twenty-five-position OLED pixel orbit plus 2/5/10-minute idle dim and black-screen protection
- Modern 280 ms fade/scale transition when rotating between portrait and landscape
- Dedicated three-column landscape control plane and adaptive landscape app/help/disk screens
- Text-first app drawer, searchable launcher and built-in help/control screens
- Biometric/device-credential vault for Codex, SSH and Debian actions
- Direct Codex CLI control over Termux + SSH (no API integration in the launcher)
- Safe terminal link bridge through `daak-open`/`open`, restricted to HTTP(S) and routed to Cromite by default
- Native DAAK Codex workspace screen for zero-context, RM-OS hub, phone-source and custom Mac paths
- Tailnet-only Mac and Windows/Lolie status, SSH and navigable `smb://lolile/kurek` SMB3 workflows
- Last-known-good Kurek index caching, so transient SMB retries never blank the disk interface
- daakLOLILE private dashboard integration
- Chrome Remote Desktop hub for the Google-account device list and touch-friendly remote control
- Android Calendar Provider agenda with a read-only-by-default launcher view
- Thunderbird notification summaries at 07:30 and every hour
- Local sender/spam filters; the launcher never sends email
- Open-Meteo current weather using coarse device location
- Adaptive 2/3/5-minute Lolie reconnect backoff
- Ten-minute expiry for mail metadata viewed inside the launcher
- Native Tailnet integration with daakREMEMBER for reading and quick-capturing notes
- Opt-in, keyword-limited WhatsApp notification-to-task capture; never sends messages
- Official WhatsApp companion-device setup, so the node can join an existing account by QR without its own SIM
- On-device Turkish dictation through Android's speech-recognition contract (tested with FUTO Voice Input)
- Five-minute cleanup for non-system apps launched from DAAK, with messaging, VPN, mail, music and input services protected
- DAAK Inbox routing: review a Codex CLI suggestion before writing dictated text to daakREMEMBER, Obsidian, both, or a Codex session
- Four user-configurable pinned application slots
- One-way daakREMEMBER task export into `DAAK-Vault/daakREMEMBER.md` for Obsidian
- Obsidian deep link into a local `DAAK-Vault`
- Right-edge launcher gesture into the control centre
- Bottom-edge upward gesture that always returns to the DAAK home screen
- Android HOME intents reset the launcher to its real home view, including the system bottom-swipe gesture
- Built-in three-option notification-sound chooser with immediate preview
- Kurek files download over encrypted SMB3 and open in the installed Android document viewer
- Scrollable Kurek folders with path-stable live refreshes
- One-tap Fossify Clock alarms and battery-light Plees sleep tracking from the control centre
- Optional daily update checks with a pinned manifest and mandatory APK SHA-256 verification
- Two-line temperature, apparent-temperature and condition display without clipped weather text
- Built-in notification-access shortcut plus safer mail-cache expiry that preserves messages arriving after a panel was viewed
- Empty-folder-safe Kurek navigation and atomic file downloads that never expose partial SMB files
- Fail-closed Magisk SSH firewall: port 8022 accepts only loopback and Tailscale `tun*` traffic, with password and forwarding disabled
- On-device `daak-selftest` command for sanitized firewall, Tailnet, Codex SSH, daakREMEMBER, Kurek TCP and browser-bridge diagnostics
- S9 Lilac Purple OLED palette, animated press feedback and inertial app/disk/intelligence-panel scrolling
- Two-row portrait pinned grid plus first-class mail, WhatsApp task and daakREMEMBER cards on the home screen
- Native DAAK intelligence panels replace Samsung-styled mail, WhatsApp and Remember summary dialogs
- Fingerprint/iris/device-credential vault shortcut is always reachable from the top bar

## Security model

DAAK NODE contains no SSH keys, passwords, OAuth tokens, Tailnet addresses or host-specific configuration. Private hosts are loaded at runtime from:

```text
/sdcard/Download/daak-node/config.properties
```

Copy `config.properties.example` to that path and edit it locally. Keep remote services bound to Tailscale, use key-only SSH, and do not expose Termux SSH directly to the public internet. The deployed Magisk service `companion/magisk/daak-sshd-firewall.sh` maintains an idempotent IPv4/IPv6 firewall and heartbeat; `companion/termux/daak-sshd` refuses to start sshd when that heartbeat is stale. Termux itself is never granted root.

Mail access is metadata-only through Android's notification listener: sender, subject and timestamp. WhatsApp processing is limited to notification text that Android has already decrypted and displayed; only explicit task-like phrases are captured. DAAK NODE never sends mail or WhatsApp messages.

The Kurek browser uses SMB3 over the private Tailnet. Its credential file lives inside Termux with mode `0600`; the launcher and repository never contain the SMB password.

daakREMEMBER traffic uses its existing HTTP snapshot/merge protocol on TCP 45831. The companion Mac service rejects non-Tailnet source addresses; Tailscale supplies the encrypted transport. DAAK NODE does not expose a new listening port.

Chrome Remote Desktop authentication and device selection stay inside Google's official Android app; DAAK NODE stores no remote-device IDs or remote desktop passwords. DAAK Inbox does not write a dictated item anywhere until the user selects a destination in its confirmation dialog.

The Android package name remains `com.firat.node` so upgrades preserve launcher state, permissions and private preferences. The old `/sdcard/Download/firat-node/config.properties` path remains a read-only compatibility fallback.

## Build

Requirements: macOS/Linux, JDK 8+ and Android SDK platform 29 with build-tools 35.0.1.

```sh
./build-apk.sh
adb install -r DAAK-NODE.apk
```

Install `companion/termux/daak-open` into Termux's `$PREFIX/bin` and link it
into the Debian proot's `/usr/local/bin`. Then use `daak-open example.com` or
the `open https://example.com` alias from either shell. Pass `--firefox` or
`--system` when a different Android handler is needed.

The build uses only Android SDK command-line tools. The generated APK is debug-signed for personal installation and is excluded from Git.
`release/update.json` is the pinned manifest published beside each APK release;
Android additionally enforces that an upgrade carries the same signing certificate.

## Companion apps

- [Termux](https://github.com/termux/termux-app)
- [Tailscale](https://github.com/tailscale/tailscale)
- [Thunderbird for Android](https://github.com/thunderbird/thunderbird-android)
- [Fossify Calendar](https://github.com/FossifyOrg/Calendar)
- [Fossify Clock](https://github.com/FossifyOrg/Clock)
- [Plees Tracker](https://gitlab.com/vmiklos/plees-tracker)
- [Material Files](https://github.com/zhanghai/MaterialFiles)
- [daakREMEMBER](https://github.com/benfirad/daakREMEMBER)
- [Obsidian](https://github.com/obsidianmd/obsidian-releases)
- [FUTO Voice Input](https://github.com/futo-org/voice-input)
- [FUTO Keyboard](https://github.com/futo-org/android-keyboard)
- [Chrome Remote Desktop](https://play.google.com/store/apps/details?id=com.google.chromeremotedesktop)

## Platform note

Android already runs on the Linux kernel. DAAK NODE uses Android for proprietary S9+ hardware support and a Debian proot userland for Linux tools. Replacing Android completely would usually sacrifice the stock camera, iris support, telephony reliability and other device-specific features.

## License

MIT
