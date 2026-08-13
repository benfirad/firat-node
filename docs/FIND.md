# DAAK Find

DAAK Find is a privacy-first location path for the Galaxy S9+. It prefers
hardware-GPS fixes and exposes no public HTTP endpoint. If GPS cannot fix
indoors, it can retain a recent Android network-provider point as an explicitly
approximate fallback. The Mac reads location through the existing Tailnet-only,
public-key SSH service on port 8022.

## Privacy boundary

- Hardware GPS is always preferred. A network-provider fallback is accepted
  only when it is at most 15 minutes old and reports at most 2 km accuracy.
  Passive-provider points remain rejected. A coarse network point cannot
  replace a precise GPS fix captured within the previous 30 minutes.
- When satellite reception is impossible indoors, DAAK Find can learn a local
  Wi-Fi anchor from a recent GPS fix. Access-point identifiers are keyed with a
  private on-device salt and never stored in plaintext. Reconnecting to the
  same stationary access point yields a conservatively labelled local,
  approximate point without contacting Google or another location service.
- Location history stays in Termux private storage with mode `0600`.
- DAAK Find itself performs no direct HTTP location request. Android's network
  provider may contact whichever network-location backend is configured on the
  phone; disable fallback with `DAAK_FIND_ALLOW_NETWORK=0` for strict GPS-only
  operation.
- The Mac polls a fixed SSH command and stores only the latest point, encrypted
  with AES-256-CBC/PBKDF2 and authenticated with HMAC-SHA256. Its random key
  lives in macOS Keychain under `com.daak.find.cache`.
- Apple Maps receives coordinates only when `daak-find open` is explicitly
  used. `show`, `cached`, and background `poll` do not contact a map provider.
- Google Find Hub is neither installed nor required by this subsystem.

Tailscale's coordination service remains part of the transport. It can observe
Tailnet connection metadata but not the SSH-encrypted location payload. Moving
to a self-hosted Headscale control plane is a separate migration.

The installed phone policy keeps Tailscale as Android's always-on VPN without
lockdown mode, exempts Tailscale and the two Termux packages from Doze, and
keeps mobile data enabled. SSH remains firewalled to loopback and `tun+`
interfaces, so always-on reachability does not expose port 8022 to Wi-Fi or
cellular networks.

## Runtime

Termux:Boot starts `daak-find daemon`. A valid fix is requested every five
minutes. A recent approximate point becomes available immediately while the
same cycle continues looking for precise GPS. Failed indoor GPS attempts never
replace a newer precise point with stale data. A direct Mac query refreshes data
older than three minutes before falling back to the last known point.

To limit battery use, precise GPS is retried every ten minutes while an
approximate fallback is available. Once hardware GPS succeeds, the normal
five-minute interval resumes.

The DAAK NODE Mac menu app compares its own low-energy Core Location updates
with the encrypted phone cache. It subtracts both devices' reported accuracy
from the measured distance, requires two consecutive samples beyond 750 m,
and rearms only after the devices return within 400 m. Points older than
20 minutes cannot trigger an alert. Location and notification permission are
requested once by macOS; the comparison remains local to the Mac.

```sh
daak-find             # fetch and show coordinates
daak-find open        # fetch, show, then open Apple Maps
daak-find cached      # never contact the phone
daak-find history 100 # retrieve the last 100 GPS points
```

`~/Applications/DAAK Find.command` provides the same explicit `open` action as
a Finder-launchable shortcut.

The S9+ cannot advertise its location after physical power loss. While powered
and connected to cellular data or Wi-Fi, it remains reachable from the Mac
through Tailscale. The phone keeps history while the Mac is asleep.

For the separate coarse-app and optional Mac-via-S9 network routing policy, see
[`LOCATION_PRIVACY.md`](LOCATION_PRIVACY.md).
