#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

BASE_URL="${TERMUX_GLIBC_REPO_URL:-https://packages-cf.termux.dev/apt/termux-glibc}"
DIST="${TERMUX_GLIBC_DIST:-glibc}"
COMPONENT="${TERMUX_GLIBC_COMPONENT:-stable}"
ARCH="${TERMUX_GLIBC_ARCH:-aarch64}"
WORK_DIR="${WORK_DIR:-$RUNTIME_DIR/.work/import-termux-glibc-support}"
OUTPUT_DIR="$RUNTIME_DIR/src/main/assets/runtime_support"
PROBE_DUMMY="$RUNTIME_DIR/build/generated/runtimeProbe/assets/runtime_support/lib/probe/libopencode_probe_dummy.so"

log() { printf '[runtime-support] %s\n' "$*"; }
die() { printf '[runtime-support] ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

need bsdtar
need curl
need python3
need shasum

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/debs" "$WORK_DIR/extract" "$WORK_DIR/root"

PACKAGES_FILE="$WORK_DIR/Packages"
PACKAGES_URL="$BASE_URL/dists/$DIST/$COMPONENT/binary-$ARCH/Packages"

log "downloading package index: $PACKAGES_URL"
curl -L --fail --silent --show-error "$PACKAGES_URL" -o "$PACKAGES_FILE"

python3 - "$PACKAGES_FILE" > "$WORK_DIR/packages.tsv" <<'PY'
from pathlib import Path
import re
import sys

packages_file = Path(sys.argv[1])
blocks = packages_file.read_text().split("\n\n")
packages = {}

for block in blocks:
    data = {}
    last = None
    for line in block.splitlines():
        if not line:
            continue
        if line.startswith(" ") and last:
            data[last] += "\n" + line
        elif ":" in line:
            key, value = line.split(":", 1)
            data[key] = value.strip()
            last = key
    name = data.get("Package")
    if name and name not in packages:
        packages[name] = data

def dependency_names(value):
    if not value:
        return []
    names = []
    for item in value.split(","):
        name = item.strip().split("|")[0].strip()
        if name:
            names.append(re.split(r"\s+", name, maxsplit=1)[0])
    return names

required_roots = ["glibc", "openssl-glibc"]
queue = list(required_roots)
seen = set()
ordered = []
ignored_missing = {"resolv-conf"}

while queue:
    name = queue.pop(0)
    if name in seen:
        continue
    seen.add(name)
    pkg = packages.get(name)
    if not pkg:
        if name in ignored_missing:
            continue
        raise SystemExit(f"missing package in glibc repo: {name}")
    ordered.append(pkg)
    for dep in dependency_names(pkg.get("Depends", "")):
        if dep not in seen:
            queue.append(dep)

for pkg in ordered:
    print("\t".join([pkg["Package"], pkg["Filename"], pkg["SHA256"]]))
PY

while IFS=$'\t' read -r package filename sha256; do
    deb="$WORK_DIR/debs/${filename##*/}"
    url="$BASE_URL/$filename"
    log "downloading $package"
    curl -L --fail --silent --show-error "$url" -o "$deb"
    actual="$(shasum -a 256 "$deb" | awk '{print $1}')"
    [[ "$actual" == "$sha256" ]] || die "sha256 mismatch for $package"

    package_extract="$WORK_DIR/extract/$package"
    mkdir -p "$package_extract"
    bsdtar -xf "$deb" -C "$package_extract"
    data_tar="$(find "$package_extract" -maxdepth 1 -name 'data.tar.*' | head -n 1)"
    [[ -n "$data_tar" ]] || die "no data.tar found in $deb"
    bsdtar -xf "$data_tar" -C "$WORK_DIR/root"
done < "$WORK_DIR/packages.tsv"

TERMUX_GLIBC_ROOT="$WORK_DIR/root/data/data/com.termux/files/usr/glibc"
[[ -d "$TERMUX_GLIBC_ROOT/lib" ]] || die "glibc lib directory missing after extraction"
[[ -f "$TERMUX_GLIBC_ROOT/lib/ld-linux-aarch64.so.1" ]] || die "ld-linux missing after extraction"
[[ -f "$TERMUX_GLIBC_ROOT/etc/ssl/certs/ca-certificates.crt" ]] || die "CA bundle missing after extraction"

rm -rf "$OUTPUT_DIR"
mkdir -p \
    "$OUTPUT_DIR/lib" \
    "$OUTPUT_DIR/lib/glibc" \
    "$OUTPUT_DIR/lib/openssl" \
    "$OUTPUT_DIR/lib/probe" \
    "$OUTPUT_DIR/share/certs" \
    "$OUTPUT_DIR/cache/providers/@ai-sdk/openai-compatible"

log "copying glibc runtime libraries"
find "$TERMUX_GLIBC_ROOT/lib" -maxdepth 1 -type f \( \
    -name 'ld-linux-aarch64.so.1' -o \
    -name '*.so' -o \
    -name '*.so.*' \
\) -print0 | while IFS= read -r -d '' file; do
    cp -L "$file" "$OUTPUT_DIR/lib/glibc/"
done
for dir in gconv locale engines-3 ossl-modules; do
    if [[ -d "$TERMUX_GLIBC_ROOT/lib/$dir" ]]; then
        cp -RL "$TERMUX_GLIBC_ROOT/lib/$dir" "$OUTPUT_DIR/lib/glibc/$dir"
    fi
done

log "copying CA bundle"
cp "$TERMUX_GLIBC_ROOT/etc/ssl/certs/ca-certificates.crt" "$OUTPUT_DIR/share/certs/ca-bundle.crt"

if compgen -G "$OUTPUT_DIR/lib/glibc/libssl.so*" >/dev/null; then
    cp "$OUTPUT_DIR"/lib/glibc/libssl.so* "$OUTPUT_DIR/lib/openssl/" || true
fi
if compgen -G "$OUTPUT_DIR/lib/glibc/libcrypto.so*" >/dev/null; then
    cp "$OUTPUT_DIR"/lib/glibc/libcrypto.so* "$OUTPUT_DIR/lib/openssl/" || true
fi

if [[ -f "$PROBE_DUMMY" ]]; then
    cp "$PROBE_DUMMY" "$OUTPUT_DIR/lib/probe/libopencode_probe_dummy.so"
else
    log "probe dummy not found; run ./gradlew :runtime:compileRuntimeProbe if Phase 0A2 needs dlopen coverage"
    : > "$OUTPUT_DIR/lib/probe/libopencode_probe_dummy.so"
fi

: > "$OUTPUT_DIR/cache/providers/@ai-sdk/openai-compatible/ready.marker"

log "installed support bundle: $OUTPUT_DIR"
log "support size: $(du -sh "$OUTPUT_DIR" | awk '{print $1}')"
log "ld.so: $(file "$OUTPUT_DIR/lib/glibc/ld-linux-aarch64.so.1")"
