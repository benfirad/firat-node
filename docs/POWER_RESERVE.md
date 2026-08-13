# DAAK Power Reserve

DAAK Power Reserve is a software approximation of a hardware-backed phone
power reserve. At 5% it can turn off expensive Android features while keeping a
tested BLE Find advertiser alive. It cannot operate after physical power loss,
battery removal, a forced shutdown, or the battery protection circuit opening.

## Safety gates

The service remains inert unless all of these conditions are true:

- `/data/adb/daak-power-reserve.enabled` exists and is root-owned.
- The phone is unplugged and at or below 5% twice, 30 seconds apart.
- `/data/adb/daak-find/advertiser.ready` contains an epoch timestamp no more
  than 180 seconds old.

If the heartbeat disappears while reserve is active, the service restores the
saved radio and display settings. Connecting power also restores immediately.
The normal 10% exit threshold protects against fuel-gauge rebound.

## Reserve state

The service saves the current Wi-Fi, cellular data, location, brightness, and
screen timeout settings. It then stops Wi-Fi tethering, disables Wi-Fi, cellular
data, and system location, clears cached apps, sets brightness to zero, and
turns the display off. Bluetooth is deliberately left enabled. Android's normal
battery protection remains authoritative; the service never falsifies the fuel
gauge or changes charging limits.

Tailscale and SSH configuration are not destroyed. They become unreachable
while both IP radios are off and return when the saved radio state is restored.

## Arm only after BLE validation

Do not arm Power Reserve until the DAAK Find advertiser has passed an unplugged
deep-sleep test and updates its heartbeat from the same health check used to
validate actual BLE packets:

```sh
su -c 'touch /data/adb/daak-power-reserve.enabled && chmod 600 /data/adb/daak-power-reserve.enabled'
```

Remove the flag to disarm. If reserve is already active, the next 30-second
cycle restores the saved state automatically.
