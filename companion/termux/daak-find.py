#!/data/data/com.termux/files/usr/bin/python
"""Privacy-first location recorder for DAAK Node.

The recorder prefers Android's hardware GPS provider through Termux:API. When
GPS cannot fix indoors, it can retain a recent Android network-provider point
as an explicitly approximate fallback. Remote access is provided separately by
the existing Tailnet-only SSH service.
"""

import argparse
import datetime as dt
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import signal
import subprocess
import sys
import time
from typing import Optional


HOME = Path(os.environ.get("HOME", "/data/data/com.termux/files/home"))
STATE_DIR = Path(os.environ.get("DAAK_FIND_STATE_DIR", HOME / ".local/state/daak-find"))
LATEST = STATE_DIR / "latest.json"
HISTORY = STATE_DIR / "history.ndjson"
STATUS = STATE_DIR / "status.json"
PIDFILE = STATE_DIR / "daemon.pid"
DAEMON_LOCK = STATE_DIR / "daemon.lock"
UPDATE_LOCK = STATE_DIR / "update.lock"
WIFI_CACHE = STATE_DIR / "wifi-locations.json"
WIFI_SALT = STATE_DIR / "wifi-key.bin"
INTERVAL = max(60, int(os.environ.get("DAAK_FIND_INTERVAL", "300")))
APPROXIMATE_INTERVAL = max(INTERVAL, int(os.environ.get("DAAK_FIND_APPROXIMATE_INTERVAL", "600")))
GPS_TIMEOUT = max(15, int(os.environ.get("DAAK_FIND_GPS_TIMEOUT", "55")))
MAX_HISTORY = max(100, int(os.environ.get("DAAK_FIND_MAX_HISTORY", "10000")))
MAX_ACCURACY = max(20.0, float(os.environ.get("DAAK_FIND_MAX_ACCURACY", "500")))
ALLOW_NETWORK_FALLBACK = os.environ.get("DAAK_FIND_ALLOW_NETWORK", "1") != "0"
NETWORK_MAX_AGE = max(60, int(os.environ.get("DAAK_FIND_NETWORK_MAX_AGE", "900")))
NETWORK_MAX_ACCURACY = max(100.0, float(os.environ.get("DAAK_FIND_NETWORK_MAX_ACCURACY", "2000")))
GPS_RETENTION_SECONDS = max(300, int(os.environ.get("DAAK_FIND_GPS_RETENTION", "1800")))
LAST_LOCATION_TIMEOUT = max(3, int(os.environ.get("DAAK_FIND_LAST_TIMEOUT", "8")))
WIFI_TIMEOUT = max(3, int(os.environ.get("DAAK_FIND_WIFI_TIMEOUT", "8")))
WIFI_LEARN_MAX_AGE = max(900, int(os.environ.get("DAAK_FIND_WIFI_LEARN_MAX_AGE", "21600")))
WIFI_FALLBACK_ACCURACY = max(100.0, float(os.environ.get("DAAK_FIND_WIFI_ACCURACY", "200")))


def ensure_state() -> None:
    STATE_DIR.mkdir(mode=0o700, parents=True, exist_ok=True)
    os.chmod(STATE_DIR, 0o700)


def atomic_json(path: Path, payload: dict) -> None:
    temporary = path.with_name(f".{path.name}.{os.getpid()}.tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    os.chmod(temporary, 0o600)
    os.replace(temporary, path)


def finite_number(value: object, name: str) -> float:
    try:
        number = float(value)
    except (TypeError, ValueError) as error:
        raise ValueError(f"invalid {name}") from error
    if not math.isfinite(number):
        raise ValueError(f"invalid {name}")
    return number


def normalize_location(
    payload: dict,
    captured_at: Optional[int] = None,
    allowed_providers: tuple[str, ...] = ("gps",),
) -> dict:
    if not isinstance(payload, dict):
        raise ValueError("location response is not an object")
    latitude = finite_number(payload.get("latitude"), "latitude")
    longitude = finite_number(payload.get("longitude"), "longitude")
    accuracy = finite_number(payload.get("accuracy"), "accuracy")
    if not -90.0 <= latitude <= 90.0:
        raise ValueError("latitude out of range")
    if not -180.0 <= longitude <= 180.0:
        raise ValueError("longitude out of range")
    provider = str(payload.get("provider", "gps")).lower()
    if provider not in allowed_providers:
        raise ValueError(f"provider rejected: {provider}")
    accuracy_limit = MAX_ACCURACY if provider == "gps" else NETWORK_MAX_ACCURACY
    if not 0.0 <= accuracy <= accuracy_limit:
        raise ValueError(f"{provider} accuracy outside policy")
    if captured_at is None:
        elapsed_ms = finite_number(payload.get("elapsedMs", 0), "elapsedMs")
        if elapsed_ms < 0:
            raise ValueError("elapsedMs out of range")
        timestamp = int(time.time() - elapsed_ms / 1000.0)
    else:
        timestamp = int(captured_at)
    record = {
        "schema": 1,
        "device": "DAAK NODE / Galaxy S9+",
        "provider": provider,
        "latitude": round(latitude, 7),
        "longitude": round(longitude, 7),
        "accuracyMeters": round(accuracy, 1),
        "capturedAt": timestamp,
        "capturedAtIso": dt.datetime.fromtimestamp(timestamp, dt.timezone.utc).isoformat(),
    }
    for source, target in (("altitude", "altitudeMeters"), ("speed", "speedMps"), ("bearing", "bearingDegrees")):
        if payload.get(source) is not None:
            try:
                record[target] = round(finite_number(payload[source], source), 2)
            except ValueError:
                pass
    return record


def read_json(path: Path) -> Optional[dict]:
    try:
        with path.open(encoding="utf-8") as handle:
            value = json.load(handle)
        return value if isinstance(value, dict) else None
    except (OSError, ValueError):
        return None


def write_status(state: str, **extra: object) -> None:
    previous = read_json(STATUS) or {}
    payload = {
        "schema": 1,
        "state": state,
        "updatedAt": int(time.time()),
        "lastSuccessAt": previous.get("lastSuccessAt", 0),
        "consecutiveFailures": previous.get("consecutiveFailures", 0),
    }
    payload.update(extra)
    atomic_json(STATUS, payload)


def trim_history() -> None:
    try:
        if HISTORY.stat().st_size < 8 * 1024 * 1024:
            return
        lines = HISTORY.read_text(encoding="utf-8").splitlines()[-MAX_HISTORY:]
        temporary = HISTORY.with_name(f".{HISTORY.name}.{os.getpid()}.tmp")
        temporary.write_text("\n".join(lines) + "\n", encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, HISTORY)
    except OSError:
        return


def persist_record(record: dict) -> dict:
    previous = read_json(LATEST)
    now = int(time.time())
    if (
        previous
        and record.get("provider") != "gps"
        and previous.get("provider") == "gps"
        and now - int(previous.get("capturedAt", 0)) <= GPS_RETENTION_SECONDS
    ):
        # Indoor network fixes can jump by hundreds of metres. Preserve a
        # recent, higher-quality satellite fix until it genuinely becomes old.
        return previous
    if previous and int(record.get("capturedAt", 0)) < int(previous.get("capturedAt", 0)):
        return previous
    duplicate = bool(
        previous
        and previous.get("provider") == record.get("provider")
        and previous.get("capturedAt") == record.get("capturedAt")
        and previous.get("latitude") == record.get("latitude")
        and previous.get("longitude") == record.get("longitude")
    )
    atomic_json(LATEST, record)
    if duplicate:
        return record
    with HISTORY.open("a", encoding="utf-8") as handle:
        os.chmod(HISTORY, 0o600)
        json.dump(record, handle, ensure_ascii=False, separators=(",", ":"))
        handle.write("\n")
        handle.flush()
        os.fsync(handle.fileno())
    trim_history()
    return record


def request_wifi_info() -> dict:
    result = subprocess.run(
        ["termux-wifi-connectioninfo"],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=WIFI_TIMEOUT,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or f"exit {result.returncode}"
        raise RuntimeError(f"Wi-Fi command failed: {detail}")
    try:
        payload = json.loads(result.stdout)
    except ValueError as error:
        raise RuntimeError("Wi-Fi command returned invalid JSON") from error
    if not isinstance(payload, dict) or payload.get("API_ERROR"):
        raise RuntimeError("Wi-Fi connection unavailable")
    return payload


def wifi_key(payload: dict) -> str:
    bssid = str(payload.get("bssid", "")).strip().lower()
    if not bssid or bssid in ("02:00:00:00:00:00", "00:00:00:00:00:00"):
        raise RuntimeError("Wi-Fi BSSID unavailable")
    try:
        salt = WIFI_SALT.read_bytes()
    except OSError:
        salt = os.urandom(32)
        temporary = WIFI_SALT.with_name(f".{WIFI_SALT.name}.{os.getpid()}.tmp")
        temporary.write_bytes(salt)
        os.chmod(temporary, 0o600)
        os.replace(temporary, WIFI_SALT)
    if len(salt) < 16:
        raise RuntimeError("invalid Wi-Fi privacy key")
    return hashlib.blake2b(bssid.encode("ascii"), key=salt, digest_size=16).hexdigest()


def read_wifi_cache() -> dict:
    payload = read_json(WIFI_CACHE)
    if not payload or payload.get("schema") != 1 or not isinstance(payload.get("anchors"), dict):
        return {"schema": 1, "anchors": {}}
    return payload


def learn_wifi_location(record: dict, wifi: Optional[dict] = None) -> None:
    if record.get("provider") != "gps":
        return
    accuracy = finite_number(record.get("accuracyMeters"), "accuracyMeters")
    if accuracy > MAX_ACCURACY:
        return
    wifi = wifi or request_wifi_info()
    key = wifi_key(wifi)
    cache = read_wifi_cache()
    anchors = cache["anchors"]
    anchors[key] = {
        "latitude": finite_number(record.get("latitude"), "latitude"),
        "longitude": finite_number(record.get("longitude"), "longitude"),
        "accuracyMeters": accuracy,
        "anchorCapturedAt": int(record.get("capturedAt", 0)),
        "updatedAt": int(time.time()),
    }
    if len(anchors) > 128:
        newest = sorted(
            anchors.items(), key=lambda item: int(item[1].get("updatedAt", 0)), reverse=True
        )[:128]
        cache["anchors"] = dict(newest)
    atomic_json(WIFI_CACHE, cache)


def wifi_fallback(wifi: Optional[dict] = None) -> Optional[dict]:
    wifi = wifi or request_wifi_info()
    anchor = read_wifi_cache()["anchors"].get(wifi_key(wifi))
    if not isinstance(anchor, dict):
        return None
    now = int(time.time())
    latitude = finite_number(anchor.get("latitude"), "latitude")
    longitude = finite_number(anchor.get("longitude"), "longitude")
    accuracy = max(
        WIFI_FALLBACK_ACCURACY,
        finite_number(anchor.get("accuracyMeters"), "accuracyMeters"),
    )
    if not -90.0 <= latitude <= 90.0 or not -180.0 <= longitude <= 180.0:
        raise RuntimeError("invalid local Wi-Fi anchor")
    return {
        "schema": 1,
        "device": "DAAK NODE / Galaxy S9+",
        "provider": "wifi-cache",
        "latitude": round(latitude, 7),
        "longitude": round(longitude, 7),
        "accuracyMeters": round(accuracy, 1),
        "capturedAt": now,
        "capturedAtIso": dt.datetime.fromtimestamp(now, dt.timezone.utc).isoformat(),
        "anchorCapturedAt": int(anchor.get("anchorCapturedAt", 0)),
    }


def request_location(provider: str, request: str, timeout: int) -> dict:
    result = subprocess.run(
        ["termux-location", "-p", provider, "-r", request],
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        timeout=timeout,
        check=False,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or f"exit {result.returncode}"
        raise RuntimeError(f"{provider} command failed: {detail}")
    if not result.stdout.strip():
        raise RuntimeError(f"{provider} returned no fix")
    try:
        raw = json.loads(result.stdout)
    except ValueError as error:
        raise RuntimeError(f"{provider} returned invalid JSON") from error
    if not isinstance(raw, dict) or raw.get("API_ERROR"):
        detail = raw.get("API_ERROR", "invalid response") if isinstance(raw, dict) else "invalid response"
        raise RuntimeError(f"{provider} unavailable: {detail}")
    return raw


def network_fallback() -> Optional[dict]:
    if not ALLOW_NETWORK_FALLBACK:
        return None
    raw = request_location("network", "last", LAST_LOCATION_TIMEOUT)
    elapsed_ms = finite_number(raw.get("elapsedMs"), "elapsedMs")
    if elapsed_ms < 0 or elapsed_ms > NETWORK_MAX_AGE * 1000:
        raise RuntimeError("network location is stale")
    return normalize_location(raw, allowed_providers=("network",))


def acquire_location(wait_for_lock: bool = True) -> dict:
    ensure_state()
    with UPDATE_LOCK.open("a+") as lock:
        os.chmod(UPDATE_LOCK, 0o600)
        lock_flags = fcntl.LOCK_EX if wait_for_lock else fcntl.LOCK_EX | fcntl.LOCK_NB
        try:
            fcntl.flock(lock.fileno(), lock_flags)
        except BlockingIOError as error:
            raise RuntimeError("GPS update already in progress") from error
        fallback = None
        wifi = None
        try:
            wifi = request_wifi_info()
            latest = read_json(LATEST)
            if (
                latest
                and latest.get("provider") == "gps"
                and int(time.time()) - int(latest.get("capturedAt", 0)) <= WIFI_LEARN_MAX_AGE
            ):
                learn_wifi_location(latest, wifi)
            fallback = wifi_fallback(wifi)
        except Exception:
            fallback = None
        if not fallback:
            try:
                fallback = network_fallback()
            except Exception:
                fallback = None
        if fallback:
            fallback = persist_record(fallback)
            write_status(
                "acquiring-gps",
                lastSuccessAt=fallback["capturedAt"],
                provider=fallback["provider"],
                accuracyMeters=fallback["accuracyMeters"],
                fallbackAvailable=True,
            )
        else:
            write_status("acquiring-gps", fallbackAvailable=False)
        try:
            raw = request_location("gps", "once", GPS_TIMEOUT)
            record = normalize_location(raw)
        except Exception as gps_error:
            if not fallback:
                raise
            previous = read_json(STATUS) or {}
            retained_gps = fallback.get("provider") == "gps"
            local_wifi = fallback.get("provider") == "wifi-cache"
            write_status(
                "ready-last-gps" if retained_gps else ("ready-local" if local_wifi else "ready-approximate"),
                lastSuccessAt=fallback["capturedAt"],
                consecutiveFailures=int(previous.get("consecutiveFailures", 0)) + 1,
                lastError=(
                    f"Precise GPS unavailable; retaining recent GPS fix ({safe_error(gps_error)})"
                    if retained_gps
                    else (
                        f"Precise GPS unavailable; using private local Wi-Fi anchor ({safe_error(gps_error)})"
                        if local_wifi
                        else f"Precise GPS unavailable; using network location ({safe_error(gps_error)})"
                    )
                ),
                provider=fallback["provider"],
                accuracyMeters=fallback["accuracyMeters"],
            )
            return fallback
        persist_record(record)
        try:
            learn_wifi_location(record, wifi)
        except Exception:
            pass
        write_status(
            "ready",
            lastSuccessAt=record["capturedAt"],
            consecutiveFailures=0,
            provider="gps",
            accuracyMeters=record["accuracyMeters"],
        )
        return record


def safe_error(error: BaseException) -> str:
    if isinstance(error, subprocess.TimeoutExpired):
        return "GPS fix timeout"
    return str(error).replace("\n", " ")[:160] or error.__class__.__name__


def run_once(wait_for_lock: bool = True) -> dict:
    try:
        return acquire_location(wait_for_lock=wait_for_lock)
    except Exception as error:
        if str(error) == "GPS update already in progress":
            raise
        previous = read_json(STATUS) or {}
        failures = int(previous.get("consecutiveFailures", 0)) + 1
        write_status("waiting-for-gps", consecutiveFailures=failures, lastError=safe_error(error))
        raise


def daemon() -> int:
    ensure_state()
    lock = DAEMON_LOCK.open("a+")
    os.chmod(DAEMON_LOCK, 0o600)
    try:
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        return 0
    PIDFILE.write_text(f"{os.getpid()}\n", encoding="ascii")
    os.chmod(PIDFILE, 0o600)
    stopping = False

    def stop(_signum: int, _frame: object) -> None:
        nonlocal stopping
        stopping = True

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    failures = 0
    try:
        while not stopping:
            try:
                record = run_once()
                failures = 0
                delay = INTERVAL if record.get("provider") == "gps" else APPROXIMATE_INTERVAL
            except Exception:
                failures += 1
                delay = min(900, 60 * (2 ** min(failures - 1, 4)))
            deadline = time.monotonic() + delay
            while not stopping and time.monotonic() < deadline:
                time.sleep(min(5, max(0, deadline - time.monotonic())))
    finally:
        try:
            PIDFILE.unlink()
        except OSError:
            pass
        write_status("stopped")
    return 0


def print_latest(refresh: bool, max_age: int) -> int:
    latest = read_json(LATEST)
    now = int(time.time())
    if refresh and (not latest or now - int(latest.get("capturedAt", 0)) > max_age):
        try:
            latest = run_once(wait_for_lock=False)
        except Exception:
            latest = read_json(LATEST)
    if not latest:
        return 1
    print(json.dumps(latest, ensure_ascii=False, separators=(",", ":")))
    return 0


def print_history(count: int) -> int:
    try:
        lines = HISTORY.read_text(encoding="utf-8").splitlines()[-count:]
    except OSError:
        return 1
    for line in lines:
        if line.strip():
            print(line)
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="DAAK Find GPS recorder")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("daemon")
    subparsers.add_parser("once")
    latest_parser = subparsers.add_parser("latest")
    latest_parser.add_argument("--refresh", action="store_true")
    latest_parser.add_argument("--max-age", type=int, default=180)
    history_parser = subparsers.add_parser("history")
    history_parser.add_argument("count", type=int, nargs="?", default=100)
    subparsers.add_parser("status")
    arguments = parser.parse_args()
    ensure_state()
    if arguments.command == "daemon":
        return daemon()
    if arguments.command == "once":
        try:
            print(json.dumps(run_once(), ensure_ascii=False, separators=(",", ":")))
            return 0
        except Exception as error:
            print(safe_error(error), file=sys.stderr)
            return 1
    if arguments.command == "latest":
        return print_latest(arguments.refresh, max(30, arguments.max_age))
    if arguments.command == "history":
        return print_history(max(1, min(arguments.count, 10000)))
    status = read_json(STATUS)
    if status:
        print(json.dumps(status, ensure_ascii=False, separators=(",", ":")))
        return 0
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
