#!/usr/bin/env python3
"""Repoint the Termux-glibc baked resolv.conf path to an app-writable location.

The bundled Termux glibc has its resolver config path compiled in as
/data/data/com.termux/files/usr/glibc/etc/resolv.conf, which does not exist on a
normal device (the runtime ships inside com.opencode.android, not Termux). With no
resolv.conf, glibc falls back to querying 127.0.0.1:53, which is refused on Android,
so every outbound DNS lookup from the bundled runtime fails (ConnectionRefused /
FailedToOpenSocket). We patch the single occurrence of that path in libc.so.6 to a
path the app owns and writes at runtime.
"""
import sys
from pathlib import Path

OLD = b"/data/data/com.termux/files/usr/glibc/etc/resolv.conf"
NEW = b"/data/data/com.opencode.android/files/resolv.conf"


def main() -> int:
    target = Path(sys.argv[1])
    data = bytearray(target.read_bytes())
    count = data.count(OLD)
    if count == 0:
        # Already patched (idempotent) if the new path is present.
        if data.count(NEW) >= 1:
            print(f"already patched: {target}")
            return 0
        print(f"ERROR: resolv.conf marker not found in {target}", file=sys.stderr)
        return 1
    if count != 1:
        print(f"ERROR: expected exactly 1 occurrence, found {count}", file=sys.stderr)
        return 1
    if len(NEW) > len(OLD):
        print("ERROR: replacement longer than original", file=sys.stderr)
        return 1

    backup = target.with_suffix(target.suffix + ".orig")
    if not backup.exists():
        backup.write_bytes(bytes(data))

    replacement = NEW + b"\x00" * (len(OLD) - len(NEW))
    idx = data.find(OLD)
    data[idx:idx + len(OLD)] = replacement
    target.write_bytes(bytes(data))
    print(f"patched {target}: {OLD.decode()} -> {NEW.decode()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
