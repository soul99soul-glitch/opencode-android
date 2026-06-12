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
NATIVE_OUTPUT_DIR="$RUNTIME_DIR/src/main/jniLibs/arm64-v8a"
PROBE_DUMMY="$RUNTIME_DIR/build/generated/runtimeProbe/assets/runtime_support/lib/probe/libopencode_probe_dummy.so"
LAUNCHER_SOURCE="$RUNTIME_DIR/tools/glibc-exec-launcher.c"

log() { printf '[runtime-support] %s\n' "$*"; }
die() { printf '[runtime-support] ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing command: $1"; }

find_ndk_clang() {
    local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
    [[ -n "$sdk" ]] || die "ANDROID_HOME or ANDROID_SDK_ROOT must point to the Android SDK"
    local ndk_root="$sdk/ndk"
    [[ -d "$ndk_root" ]] || die "No Android NDK directory at $ndk_root"
    local ndk_dir
    ndk_dir="$(find "$ndk_root" -mindepth 1 -maxdepth 1 -type d | sort | tail -n 1)"
    [[ -n "$ndk_dir" ]] || die "No Android NDK installed under $ndk_root"

    local host_tag
    case "$(uname -s)" in
        Darwin) host_tag="darwin-x86_64" ;;
        Linux) host_tag="linux-x86_64" ;;
        *) die "Unsupported host OS: $(uname -s)" ;;
    esac

    local clang="$ndk_dir/toolchains/llvm/prebuilt/$host_tag/bin/aarch64-linux-android26-clang"
    [[ -x "$clang" ]] || die "Missing NDK clang: $clang"
    printf '%s\n' "$clang"
}

patch_glibc_resolver_path() {
    local libc="$1"
    python3 - "$libc" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
old = b"/data/data/com.termux/files/usr/glibc/etc/resolv.conf"
new = b"/data/data/com.opencode.android/files/resolv.conf"
if len(new) > len(old):
    raise SystemExit("replacement resolver path is longer than original")
data = path.read_bytes()
count = data.count(old)
if count != 1:
    raise SystemExit(f"expected one resolver path in {path}, found {count}")
path.write_bytes(data.replace(old, new + b"\0" * (len(old) - len(new))))
PY
}

need bsdtar
need curl
need file
need python3
need shasum

[[ -f "$LAUNCHER_SOURCE" ]] || die "missing glibc launcher source: $LAUNCHER_SOURCE"

rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR/debs" "$WORK_DIR/extract" "$WORK_DIR/root" "$NATIVE_OUTPUT_DIR"

CLANG="$(find_ndk_clang)"

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

required_roots = ["glibc", "openssl-glibc", "git-glibc", "zstd-glibc"]
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
    "$OUTPUT_DIR/bin" \
    "$OUTPUT_DIR/lib" \
    "$OUTPUT_DIR/lib/glibc" \
    "$OUTPUT_DIR/lib/openssl" \
    "$OUTPUT_DIR/lib/probe" \
    "$OUTPUT_DIR/libexec/git-core" \
    "$OUTPUT_DIR/share/certs" \
    "$OUTPUT_DIR/share/opencode-runtime" \
    "$OUTPUT_DIR/tool_payload/bin" \
    "$OUTPUT_DIR/tool_payload/libexec/git-core" \
    "$OUTPUT_DIR/cache/providers/@ai-sdk/openai-compatible"

log "copying glibc runtime libraries"
find "$TERMUX_GLIBC_ROOT/lib" -maxdepth 1 -type f \( \
    -name 'ld-linux-aarch64.so.1' -o \
    -name '*.so' -o \
    -name '*.so.*' \
\) -print0 | while IFS= read -r -d '' file; do
    cp -L "$file" "$OUTPUT_DIR/lib/glibc/"
done
install -m 755 "$TERMUX_GLIBC_ROOT/lib/ld-linux-aarch64.so.1" "$NATIVE_OUTPUT_DIR/libglibc_loader.so"
patch_glibc_resolver_path "$OUTPUT_DIR/lib/glibc/libc.so.6"
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

log "copying git support files"
if [[ -d "$TERMUX_GLIBC_ROOT/share/git-core" ]]; then
    cp -RL "$TERMUX_GLIBC_ROOT/share/git-core" "$OUTPUT_DIR/share/git-core"
fi

rm -f "$NATIVE_OUTPUT_DIR"/libgit*.so
"$CLANG" -O2 -Wall -Wextra -o "$NATIVE_OUTPUT_DIR/libgit.so" "$LAUNCHER_SOURCE" -s
file "$NATIVE_OUTPUT_DIR/libgit.so" | grep -q 'interpreter /system/bin/linker64' ||
    die "git launcher is not an Android executable: $(file "$NATIVE_OUTPUT_DIR/libgit.so")"
tool_map="$OUTPUT_DIR/share/opencode-runtime/git-tools.tsv"
: > "$tool_map"

copy_git_payload() {
    local rel="$1"
    local source="$TERMUX_GLIBC_ROOT/$rel"
    [[ -f "$source" ]] || return 0
    if ! file "$source" | grep -q 'ELF 64-bit'; then
        return 0
    fi
    install -m 644 "$source" "$OUTPUT_DIR/tool_payload/$rel"
    printf '%s\t%s\n' "$rel" "libgit.so" >> "$tool_map"
}

copy_git_payload "bin/git"
if [[ -d "$TERMUX_GLIBC_ROOT/libexec/git-core" ]]; then
    while IFS= read -r -d '' helper; do
        rel="${helper#$TERMUX_GLIBC_ROOT/}"
        copy_git_payload "$rel"
    done < <(find "$TERMUX_GLIBC_ROOT/libexec/git-core" -maxdepth 1 -type f -print0)
fi

grep -q $'^bin/git\t' "$tool_map" || die "git executable was not imported"
grep -q $'^libexec/git-core/git-remote-http\t' "$tool_map" || die "git remote-http helper was not imported"
if ! grep -q $'^libexec/git-core/git-remote-https\t' "$tool_map"; then
    cp "$OUTPUT_DIR/tool_payload/libexec/git-core/git-remote-http" "$OUTPUT_DIR/tool_payload/libexec/git-core/git-remote-https"
    printf '%s\t%s\n' "libexec/git-core/git-remote-https" "libgit.so" >> "$tool_map"
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
