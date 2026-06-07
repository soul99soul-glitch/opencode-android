#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
RUNTIME_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_DIR="$(cd "$RUNTIME_DIR/../.." && pwd)"

VERSION="${1:-1.14.20}"
SUPPORT_DIR="${RUNTIME_SUPPORT_DIR:-}"
WORK_DIR="${WORK_DIR:-$RUNTIME_DIR/.work/import-opencode-runtime}"
OUTPUT_DIR="$RUNTIME_DIR/src/main/jniLibs/arm64-v8a"
OUTPUT_BIN="$OUTPUT_DIR/libopencode_runtime.so"
SUPPORT_OUTPUT="$RUNTIME_DIR/src/main/assets/runtime_support"
PREBUILT_WRAPPER="$REPO_DIR/build/tools/prebuilt/wrapper"
PREBUILT_SHIM="$REPO_DIR/build/tools/prebuilt/bunfs_shim.so"

log() { printf '[runtime-import] %s\n' "$*"; }
die() { printf '[runtime-import] ERROR: %s\n' "$*" >&2; exit 1; }
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

patch_wrapper_for_runtime_root() {
    local wrapper_c="$1"
    python3 - "$wrapper_c" <<'PY'
from pathlib import Path
import sys

path = Path(sys.argv[1])
text = path.read_text()
text = text.replace("<<<<<<< HEAD\n", "").replace("=======\n", "").replace(">>>>>>> HEAD\n", "")
text = text.replace(
    '#define LD_SO     "/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1"\n'
    '#define GLIBC_LIB "/data/data/com.termux/files/usr/glibc/lib"\n',
    '#define DEFAULT_LD_SO     "/data/data/com.termux/files/usr/glibc/lib/ld-linux-aarch64.so.1"\n'
    '#define DEFAULT_GLIBC_LIB "/data/data/com.termux/files/usr/glibc/lib"\n',
)
needle = '''static void die(const char *msg) {
    write(STDERR_FILENO, "error: ", 7);
    write(STDERR_FILENO, msg, strlen(msg));
    write(STDERR_FILENO, "\\n", 1);
    _exit(1);
}
'''
replacement = needle + '''
static const char *env_or_default(const char *name, const char *fallback) {
    const char *value = getenv(name);
    return (value && value[0]) ? value : fallback;
}
'''
if "env_or_default" not in text:
    text = text.replace(needle, replacement)
text = text.replace(
    "    /* Build argv for ld.so */\n",
    '    /* Build argv for ld.so */\n'
    '    const char *ld_so = env_or_default("GLIBC_LD_SO", DEFAULT_LD_SO);\n'
    '    const char *glibc_lib = env_or_default("GLIBC_LIB_PATH", DEFAULT_GLIBC_LIB);\n',
)
text = text.replace("    new_argv[na++] = LD_SO;\n", "    new_argv[na++] = ld_so;\n")
text = text.replace("    new_argv[na++] = GLIBC_LIB;\n", "    new_argv[na++] = glibc_lib;\n")
text = text.replace("    userland_exec(LD_SO, new_argv, na, new_envp, ne);", "    userland_exec(ld_so, new_argv, na, new_envp, ne);")
if "new_argv[na++] = LD_SO" in text or "new_argv[na++] = GLIBC_LIB" in text or "userland_exec(LD_SO" in text:
    raise SystemExit("wrapper patch left stale LD_SO/GLIBC_LIB references")
path.write_text(text)
PY
}

copy_support_bundle() {
    local source="$1"
    [[ -d "$source" ]] || die "RUNTIME_SUPPORT_DIR does not exist: $source"
    rm -rf "$SUPPORT_OUTPUT"
    mkdir -p "$(dirname "$SUPPORT_OUTPUT")"
    cp -R "$source" "$SUPPORT_OUTPUT"
    log "copied support bundle: $source -> $SUPPORT_OUTPUT"
}

need npm
need git
need python3
need tar
need file

[[ -x "$PREBUILT_WRAPPER" ]] || die "missing prebuilt wrapper: $PREBUILT_WRAPPER"
[[ -f "$PREBUILT_SHIM" ]] || die "missing prebuilt bunfs shim: $PREBUILT_SHIM"

CLANG="$(find_ndk_clang)"
rm -rf "$WORK_DIR"
mkdir -p "$WORK_DIR" "$OUTPUT_DIR"

log "downloading opencode-linux-arm64@$VERSION"
(
    cd "$WORK_DIR"
    npm pack "opencode-linux-arm64@$VERSION" >/dev/null
    tar -xzf opencode-linux-arm64-*.tgz
)

RAW="$WORK_DIR/package/bin/opencode"
[[ -x "$RAW" ]] || die "npm package did not contain executable package/bin/opencode"
file "$RAW" | grep -q 'interpreter /lib/ld-linux-aarch64.so.1' ||
    die "unexpected raw binary format: $(file "$RAW")"

log "cloning bun-termux-loader"
git clone --depth 1 https://github.com/Hope2333/bun-termux-loader "$WORK_DIR/loader" >/dev/null 2>&1
patch_wrapper_for_runtime_root "$WORK_DIR/loader/wrapper.c"

log "building RUNTIME_ROOT-aware wrapper"
"$CLANG" -O2 -Wall -Wextra -o "$WORK_DIR/loader/wrapper-runtime" "$WORK_DIR/loader/wrapper.c" -s
file "$WORK_DIR/loader/wrapper-runtime" | grep -q 'interpreter /system/bin/linker64' ||
    die "patched wrapper is not an Android executable: $(file "$WORK_DIR/loader/wrapper-runtime")"

log "wrapping OpenCode"
python3 "$WORK_DIR/loader/build.py" \
    "$RAW" \
    "$WORK_DIR/opencode-termux" \
    --wrapper "$WORK_DIR/loader/wrapper-runtime" \
    --shim "$PREBUILT_SHIM"

install -m 755 "$WORK_DIR/opencode-termux" "$OUTPUT_BIN"
log "installed runtime executable: $OUTPUT_BIN"
log "$(file "$OUTPUT_BIN")"
log "size: $(du -h "$OUTPUT_BIN" | awk '{print $1}')"

if [[ -n "$SUPPORT_DIR" ]]; then
    copy_support_bundle "$SUPPORT_DIR"
else
    log "no RUNTIME_SUPPORT_DIR supplied; keeping generated probe placeholder support"
fi

log "next: cd $REPO_DIR/android-app && ./gradlew :runtime:assembleDebug"
