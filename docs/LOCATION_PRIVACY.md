# Location privacy profile

DAAK NODE keeps two location planes separate:

- **Private truth:** Android GPS and the permitted network fallback are read by
  the Termux shared UID for DAAK Find. Records remain `0600` in Termux and the
  only authorized SSH key is the owner's Mac key. The Mac cache is encrypted
  and authenticated with a Keychain-held secret.
- **Application view:** third-party applications that previously had location
  permission retain coarse foreground access but lose precise permission.
  Applications that had no permission receive no new permission. Google Play
  services' effective location AppOp is denied.
- **Network view:** the Galaxy S9+ is an approved Tailscale exit node. Selecting
  it from the DAAK NODE Mac menu is optional and changes only IP-based
  geolocation. It does not spoof Core Location, Find My, GNSS, or sensor data.

No mock-location provider is installed. Mocking Android's system position would
also poison the private DAAK Find record and the Mac separation alarm.

The Magisk boot hook reapplies the enabled profile after reboot. On-device
administration is reversible:

```sh
su -c '/system/bin/sh /data/adb/daak-location-privacy.sh status'
su -c '/system/bin/sh /data/adb/daak-location-privacy.sh disable'
su -c '/system/bin/sh /data/adb/daak-location-privacy.sh enable'
```

An individual application can temporarily receive exact foreground access with
`allow-exact PACKAGE`; `restrict PACKAGE` returns it to coarse foreground
access.
