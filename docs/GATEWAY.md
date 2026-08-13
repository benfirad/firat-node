# DAAK Node mobile gateway

DAAK Node can keep the S9+ Wi-Fi hotspot available without opening Samsung's
Settings UI. The implementation uses Android's own tethering binder; it does
not patch carrier entitlement, APNs, quotas, or metering.

## Safety model

- The watchdog is disabled unless `/data/adb/daak-gateway.enabled` exists.
- It waits for registered cellular data before starting the hotspot.
- It pauses at 46 °C battery temperature and resumes below 41 °C.
- When unplugged, it pauses at 15% battery and resumes at 25%.
- Management SSH and ADB remain restricted to loopback and Tailscale by the
  existing dual-stack firewall.
- The status file is `/data/adb/daak-gateway/status`; it contains no SSID,
  password, phone number, IMSI, APN, or location.

## Install before the SIM arrives

Build `tools/android/build-gateway-control.sh`, then install the resulting DEX
JAR and `companion/magisk/daak-gateway-watchdog.sh` as root. Leave the enable
flag absent. This is the deployed default.

## Enable after inserting the gateway SIM

First configure a WPA2 password, join the hotspot once from the Mac, and verify
mobile data and the carrier's tethering terms. Then create the root-owned flag:

```sh
su -c 'touch /data/adb/daak-gateway.enabled && chmod 600 /data/adb/daak-gateway.enabled'
```

Remove the flag to stop automatic recovery. Removing it intentionally does not
turn off a hotspot that the user enabled manually.
