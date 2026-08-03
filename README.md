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
- daakLOLILE private dashboard integration with a live availability check and Kurek/Remote offline recovery
- Chrome Remote Desktop hub for the Google-account device list and touch-friendly remote control
- Biometric-gated Lolie/Mac Wake-on-LAN and key-only SSH shutdown controls
- Google Calendar Provider agenda with a read-only launcher view and an Obsidian Markdown mirror
- Weather-country public/special days merged into the agenda from a weekly Nager.Date cache; no silent cloud-calendar writes
- Gmail + Thunderbird notification summaries at 07:30 and every hour, with MessagingStyle/InboxStyle parsing, duplicate suppression and per-source bridge health
- Unified MediaSession controls and source chooser for Auxio, YouTube Music and Spotify; Auxio remains the full-offline library for user-owned files
- Secure true-black OLED lock player with artwork, progress, previous/play-pause/next controls and automatic screen-off activation while media is playing; Android/Knox keyguard remains locked underneath
- Local sender/spam filters; the launcher never sends email
- Open-Meteo current weather using coarse device location
- Adaptive 2/3/5-minute Lolie reconnect backoff
- A rotating 24-hour, 40-item local mail-metadata buffer; sender/subject summaries never leave the phone and the launcher has no send path
- Native Tailnet integration with daakREMEMBER for reading, adding, editing, completing, deleting and undoing deleted notes
- Opt-in, keyword-limited WhatsApp notification-to-task capture; never sends messages
- Official WhatsApp companion-device setup, so the node can join an existing account by QR without its own SIM
- On-device Turkish dictation through Android's speech-recognition contract (tested with FUTO Voice Input)
- Ten-minute cleanup for ordinary non-system apps launched from DAAK, with a narrow root-mediated 15-second force-stop for Image Toolbox, Material Files and Google Photos; messaging, VPN, mail, music and input services stay protected
- DAAK Inbox routing: review a Codex CLI suggestion before writing dictated text to daakREMEMBER, Obsidian, both, or a Codex session
- Four user-configurable pinned application slots
- One-way daakREMEMBER task export into `DAAK-Vault/daakREMEMBER.md` for Obsidian
- Obsidian deep link into a local `DAAK-Vault`
- Right-edge launcher gesture into the control centre
- Bottom-edge upward gesture that always returns to the DAAK home screen
- Android HOME intents reset the launcher to its real home view, including the system bottom-swipe gesture
- Three normalized, embedded notification tones with immediate preview and a versioned high-importance Android channel
- Kurek files can stream through a localhost-only, three-minute encrypted SMB3 preview rendered inline by Fennec/Firefox without a persistent phone copy, or download explicitly for offline use
- `Kitap Meraklısına` keeps a resumable, non-deleting 2.79 GiB offline mirror under `Documents/DAAK-Vault`; Android JobScheduler runs lightweight six-hour checks only on unmetered Wi-Fi while charging, plus a weekly integrity reconciliation
- Book's Story is integrated into the Kurek/Books panel with a Turkish, true-black OLED reading profile and direct access to the local `Kitap Meraklısına` library; EPUB, PDF, FB2, TXT, HTML and Markdown stay on-device
- Scrollable Kurek folders with path-stable live refreshes
- One-tap Fossify Clock alarms and battery-light Plees sleep tracking from the control centre
- Optional daily update checks with a pinned manifest and mandatory APK SHA-256 verification
- Two-line temperature, apparent-temperature and condition display without clipped weather text
- Built-in notification-access shortcut plus safer mail-cache expiry that preserves messages arriving after a panel was viewed
- Empty-folder-safe Kurek navigation and atomic file downloads that never expose partial SMB files
- Fail-closed Magisk firewall: SSH 8022 and opt-in secure ADB 5555 accept only loopback and Tailscale `tun*` traffic
- Chrome Remote Desktop from the phone to computers, plus account-free scrcpy control from the Mac to the phone over Tailscale
- On-device `daak-selftest` command for sanitized firewall, remote ADB, Tailnet, Codex SSH, daakREMEMBER, Kurek TCP and browser-bridge diagnostics
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

The OLED player uses Android's active MediaSession and `showWhenLocked`; it never dismisses or replaces the secure keyguard. Back/exit returns to the normal fingerprint, iris or PIN lock screen. Its automatic trigger runs only on `SCREEN_OFF` while a session is actively playing.

The Kurek browser uses SMB3 over the private Tailnet. Its credential file lives inside Termux with mode `0600`; the launcher and repository never contain the SMB password.

daakREMEMBER traffic uses its existing HTTP snapshot/merge protocol on TCP 45831. The companion Mac service rejects non-Tailnet source addresses; Tailscale supplies the encrypted transport. DAAK NODE does not expose a new listening port.

Chrome Remote Desktop authentication and device selection stay inside Google's official Android app; DAAK NODE stores no remote-device IDs or remote desktop passwords. DAAK Inbox does not write a dictated item anywhere until the user selects a destination in its confirmation dialog.

Mac-to-phone control uses Android's authorized ADB key plus the Tailnet-only firewall. Run `companion/macos/daak-phone`; it discovers the online Galaxy S9+ through the local Tailscale CLI and launches scrcpy. Port 5555 is never accepted from Wi-Fi, cellular or the public internet. Remove `/data/adb/daak-remote-adb.enabled` and restart the Magisk service to disable it.

Official YouTube Music and Spotify offline downloads remain inside their own applications. They are not copied into Auxio because those private app stores are encrypted and service-controlled; DAAK only provides shared playback controls and safe source switching.

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
- [Google Calendar](https://play.google.com/store/apps/details?id=com.google.android.calendar)
- [Fossify Clock](https://github.com/FossifyOrg/Clock)
- [Plees Tracker](https://gitlab.com/vmiklos/plees-tracker)
- [Material Files](https://github.com/zhanghai/MaterialFiles)
- [Book's Story](https://github.com/Acclorite/book-story)
- [daakREMEMBER](https://github.com/benfirad/daakREMEMBER)
- [Obsidian](https://github.com/obsidianmd/obsidian-releases)
- [FUTO Voice Input](https://github.com/futo-org/voice-input)
- [FUTO Keyboard](https://github.com/futo-org/android-keyboard)
- [Chrome Remote Desktop](https://play.google.com/store/apps/details?id=com.google.chromeremotedesktop)

## Platform note

Android already runs on the Linux kernel. DAAK NODE uses Android for proprietary S9+ hardware support and a Debian proot userland for Linux tools. Replacing Android completely would usually sacrifice the stock camera, iris support, telephony reliability and other device-specific features.

## License

MIT
