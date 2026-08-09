# Bixby button → projectless Codex

The Galaxy S9/S9+ firmware consumes Samsung's `WINK` key before Android
accessibility services can receive it. `install-bixby-codex.sh` creates a
recoverable Magisk overlay from the phone's own `Generic.kl`, replacing only
scan code `703 WINK` with `703 F1`. DAAK Node still requires scan code 703, so
an external keyboard's F1 key cannot open Codex.

Install from a root shell and reboot:

```sh
su -c /path/to/install-bixby-codex.sh
reboot
```

Remove without touching `/system`:

```sh
su -c '/path/to/install-bixby-codex.sh --uninstall'
reboot
```

The resulting Codex session remains protected by DAAK Node's biometric/device
credential vault and uses the `standalone` workspace mode.
