#!/usr/bin/env python3
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile


with tempfile.TemporaryDirectory() as state:
    os.environ["DAAK_FIND_STATE_DIR"] = state
    module_path = Path(__file__).parents[1] / "companion/termux/daak-find.py"
    spec = importlib.util.spec_from_file_location("daak_find", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)

    valid = module.normalize_location(
        {"provider": "gps", "latitude": 41.0, "longitude": 29.0, "accuracy": 12.5},
        captured_at=1_700_000_000,
    )
    assert valid["provider"] == "gps"
    assert valid["accuracyMeters"] == 12.5

    approximate = module.normalize_location(
        {"provider": "network", "latitude": 41.0, "longitude": 29.0, "accuracy": 100.0, "elapsedMs": 1000},
        captured_at=1_700_000_000,
        allowed_providers=("network",),
    )
    assert approximate["provider"] == "network"
    assert approximate["accuracyMeters"] == 100.0

    for invalid in (
        {"provider": "network", "latitude": 41, "longitude": 29, "accuracy": 10},
        {"provider": "gps", "latitude": 91, "longitude": 29, "accuracy": 10},
        {"provider": "gps", "latitude": 41, "longitude": 181, "accuracy": 10},
        {"provider": "gps", "latitude": 41, "longitude": 29, "accuracy": 9999},
        {"provider": "network", "latitude": 41, "longitude": 29, "accuracy": 9999},
    ):
        try:
            module.normalize_location(invalid)
        except ValueError:
            pass
        else:
            raise AssertionError(f"unsafe location accepted: {invalid}")

    fake_bin = Path(state) / "bin"
    fake_bin.mkdir()
    fake_location = fake_bin / "termux-location"
    fake_location.write_text(
        "#!/bin/sh\n"
        "case \"$*\" in\n"
        "  *'-p network'*) printf '%s\\n' "
        "'{\"provider\":\"network\",\"latitude\":40.0,\"longitude\":29.0,\"accuracy\":100,\"elapsedMs\":1000}' ;;\n"
        "  *) printf '%s\\n' "
        "'{\"provider\":\"gps\",\"latitude\":40.1,\"longitude\":29.2,\"accuracy\":8,\"elapsedMs\":0}' ;;\n"
        "esac\n",
        encoding="utf-8",
    )
    fake_location.chmod(0o700)
    os.environ["PATH"] = f"{fake_bin}:{os.environ['PATH']}"
    recorded = module.acquire_location()
    assert recorded["provider"] == "gps"
    assert json.loads(module.LATEST.read_text(encoding="utf-8"))["latitude"] == 40.1
    assert len(module.HISTORY.read_text(encoding="utf-8").splitlines()) == 2
    assert module.LATEST.stat().st_mode & 0o777 == 0o600
    assert module.HISTORY.stat().st_mode & 0o777 == 0o600

    # A coarse indoor network point must not displace a recent precise fix.
    retained = module.persist_record(
        module.normalize_location(
            {"provider": "network", "latitude": 39.0, "longitude": 28.0, "accuracy": 500},
            captured_at=recorded["capturedAt"] + 10,
            allowed_providers=("network",),
        )
    )
    assert retained["provider"] == "gps"
    assert json.loads(module.LATEST.read_text(encoding="utf-8"))["provider"] == "gps"

    def network_when_gps_times_out(provider: str, request: str, timeout: int) -> dict:
        if provider == "network":
            return {
                "provider": "network",
                "latitude": 39.0,
                "longitude": 28.0,
                "accuracy": 500,
                "elapsedMs": 1000,
            }
        raise subprocess.TimeoutExpired("termux-location", timeout)

    module.request_location = network_when_gps_times_out
    retained_after_timeout = module.acquire_location()
    assert retained_after_timeout["provider"] == "gps"
    retained_status = json.loads(module.STATUS.read_text(encoding="utf-8"))
    assert retained_status["state"] == "ready-last-gps"
    assert retained_status["provider"] == "gps"

    for path in (module.LATEST, module.HISTORY, module.STATUS):
        try:
            path.unlink()
        except FileNotFoundError:
            pass

    def fallback_only(provider: str, request: str, timeout: int) -> dict:
        if provider == "network":
            return {
                "provider": "network",
                "latitude": 40.2,
                "longitude": 29.3,
                "accuracy": 120,
                "elapsedMs": 2000,
            }
        raise subprocess.TimeoutExpired("termux-location", timeout)

    module.request_location = fallback_only
    fallback = module.acquire_location()
    assert fallback["provider"] == "network"
    fallback_status = json.loads(module.STATUS.read_text(encoding="utf-8"))
    assert fallback_status["state"] == "ready-approximate"
    assert fallback_status["provider"] == "network"
    assert fallback_status["accuracyMeters"] == 120.0

    # A stationary Wi-Fi anchor provides a private indoor fallback. The BSSID
    # must never appear in the cache stored on the phone.
    wifi_bssid = "12:34:56:78:9a:bc"
    module.request_wifi_info = lambda: {"bssid": wifi_bssid, "rssi": -60}
    module.learn_wifi_location(recorded)
    local = module.wifi_fallback()
    assert local is not None
    assert local["provider"] == "wifi-cache"
    assert local["accuracyMeters"] >= 200.0
    assert wifi_bssid not in module.WIFI_CACHE.read_text(encoding="utf-8")
    assert module.WIFI_CACHE.stat().st_mode & 0o777 == 0o600
    assert module.WIFI_SALT.stat().st_mode & 0o777 == 0o600

print("DAAK Find policy OK")
