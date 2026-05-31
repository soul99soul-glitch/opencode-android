#!/data/data/com.termux/files/usr/bin/bash
# tools/produce-local.sh — Build OpenCode for Termux
# Downloads from npm + wraps with bun-termux-loader
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="$ROOT_DIR/artifacts/opencode/runtime"
OPENCODE_OUT="$RUNTIME_DIR/opencode-termux"
INPUT_VER="${1:-}"

log() { printf '[produce] %s\n' "$*"; }
die() { printf '[produce] ERROR: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null 2>&1 || die "missing: $1"; }

[[ -z "$INPUT_VER" ]] && INPUT_VER="$(npm view opencode-linux-arm64 version 2>/dev/null || true)"
[[ -n "$INPUT_VER" ]] || die "no version specified"
VER="$INPUT_VER"

CACHE_DIR="${CACHE_DIR:-$HOME/.cache/opencode-termux}"
LOADER_DIR="/data/data/com.termux/files/home/bun-termux-loader"
EXTRACT="${TMPDIR:-$PREFIX/tmp}/produce-$$"
mkdir -p "$RUNTIME_DIR" "$CACHE_DIR" "$EXTRACT"
trap 'rm -rf $EXTRACT' EXIT

log "opencode v$VER"

# Check cache
CACHE_BIN="$CACHE_DIR/opencode-$VER"
if [[ -f "$CACHE_BIN" ]]; then
	log "cache hit"
	install -m 755 "$CACHE_BIN" "$OPENCODE_OUT"
	"$OPENCODE_OUT" --version 2>/dev/null || true
	rm -rf "$ROOT_DIR/artifacts/staged" "$ROOT_DIR/packaging/dpkg/work" "$ROOT_DIR/packaging/pacman/src"
	log "DONE"
	exit 0
fi

# Download from npm
need npm
cd "$EXTRACT"
log "downloading opencode-linux-arm64@$VER from npm"
npm pack "opencode-linux-arm64@$VER" >/dev/null 2>&1 || die "npm pack failed"
tar -xzf opencode-linux-arm64-*.tgz 2>/dev/null
RAW="package/bin/opencode"
[[ -f "$RAW" && -x "$RAW" ]] || die "binary not found"

# Wrap with bun-termux-loader
if [[ ! -f "$LOADER_DIR/build.py" ]]; then
	log "cloning bun-termux-loader"
	git clone --depth 1 https://github.com/Hope2333/bun-termux-loader "$EXTRACT/loader" 2>/dev/null || die "clone failed"
	LOADER_DIR="$EXTRACT/loader"
fi

log "wrapping for Termux"
python3 "$LOADER_DIR/build.py" "$RAW" --wrapper "$LOADER_DIR/wrapper" --shim "$LOADER_DIR/bunfs_shim.so" 2>&1 | tail -3
WRAPPED="${RAW}-termux"
[[ -f "$WRAPPED" ]] || die "wrapping failed"

install -m 755 "$WRAPPED" "$OPENCODE_OUT"
install -m 755 "$WRAPPED" "$CACHE_BIN"
log "done: $(file "$OPENCODE_OUT" | cut -d: -f2)"
log "version: $("$OPENCODE_OUT" --version 2>/dev/null || echo '?')"

rm -rf "$ROOT_DIR/artifacts/staged" "$ROOT_DIR/packaging/dpkg/work" "$ROOT_DIR/packaging/pacman/src"
log "DONE"
