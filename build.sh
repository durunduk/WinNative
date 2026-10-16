#!/usr/bin/env bash
# One-command local build for this fork.
#
#   ./build.sh                  build the "experimental" flavor
#   ./build.sh -u               first update to the latest commit of the current branch on GitHub
#   ./build.sh -i               install on a phone connected over adb after building
#   ./build.sh -f standard      build another flavor (standard, ludashi, pubg, antutu, experimental)
#   ./build.sh -r               release build instead of debug
#
# Options can be combined: ./build.sh -u -i
# Finished APKs are copied to ~/WinNative-builds (override with OUT_DIR=...).

set -euo pipefail

FLAVOR="experimental"
BUILD_TYPE="Debug"
UPDATE=0
INSTALL=0
OUT_DIR="${OUT_DIR:-$HOME/WinNative-builds}"

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit "${1:-0}"; }

while getopts ":f:uirh" opt; do
	case "$opt" in
		f) FLAVOR="$OPTARG" ;;
		u) UPDATE=1 ;;
		i) INSTALL=1 ;;
		r) BUILD_TYPE="Release" ;;
		h) usage 0 ;;
		*) usage 1 ;;
	esac
done

case "$FLAVOR" in
	standard|ludashi|pubg|antutu|experimental) ;;
	*) echo "Unknown flavor '$FLAVOR'"; usage 1 ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$ROOT"

step() { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[1;31mERROR:\033[0m %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------- environment
step "Checking tools"

[ -f "$HOME/.cargo/env" ] && . "$HOME/.cargo/env"

command -v git >/dev/null || fail "git is not installed (sudo apt install git)"
git lfs version >/dev/null 2>&1 || fail "git-lfs is not installed (sudo apt install git-lfs)"
command -v cargo >/dev/null || fail "Rust is not installed. Run:
  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y
  source \"\$HOME/.cargo/env\" && rustup target add aarch64-linux-android"
rustup target list --installed 2>/dev/null | grep -qx aarch64-linux-android \
	|| { echo "Adding Rust target aarch64-linux-android"; rustup target add aarch64-linux-android; }

# Gradle runs on JAVA_HOME when it is set, otherwise on the java in PATH.
java_bin="java"
[ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ] && java_bin="$JAVA_HOME/bin/java"
java_major="$("$java_bin" -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)"
[ -n "$java_major" ] || fail "Java is not installed (sudo apt install openjdk-17-jdk)"
if [ "$java_major" != "17" ]; then
	echo "Warning: WinNative recommends JDK 17, Gradle will use JDK $java_major. If the build fails, install"
	echo "         openjdk-17-jdk and pick it with 'sudo update-alternatives --config java'."
fi

if [ ! -f local.properties ] || ! grep -q '^sdk.dir=' local.properties; then
	sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
	[ -n "$sdk" ] && [ -d "$sdk" ] || fail "Android SDK not found: set ANDROID_HOME or write sdk.dir=... into local.properties"
	echo "sdk.dir=$sdk" > local.properties
	echo "Created local.properties (sdk.dir=$sdk)"
fi

# ---------------------------------------------------------------- source tree
branch="$(git rev-parse --abbrev-ref HEAD)"

if [ "$UPDATE" = 1 ]; then
	step "Updating '$branch' from GitHub"
	[ "$branch" != "HEAD" ] || fail "Not on a branch (detached HEAD); run 'git checkout feature/ue5-tweaks' first"
	if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
		git status --short --untracked-files=no
		fail "You have local changes (listed above). Commit or stash them, or run without -u."
	fi
	git fetch origin "$branch"
	# The branch is rebased onto new WinNative releases, so follow it exactly.
	git reset --hard "origin/$branch"
fi

step "Fetching Git LFS files and submodules"
git lfs install --local >/dev/null
git lfs pull
git submodule update --init --recursive

imagefs="app/src/main/assets/imagefs.tzst"
size="$(stat -c %s "$imagefs" 2>/dev/null || echo 0)"
[ "$size" -gt 1000000 ] || fail "$imagefs is only $size bytes: Git LFS did not download it. Check 'git lfs pull' output above."

# ---------------------------------------------------------------- build
flavor_cap="$(tr '[:lower:]' '[:upper:]' <<< "${FLAVOR:0:1}")${FLAVOR:1}"
task="assemble${flavor_cap}${BUILD_TYPE}"
build_type_dir="$(tr '[:upper:]' '[:lower:]' <<< "$BUILD_TYPE")"

step "Building $task ($(git log -1 --format='%h %s'))"
# A daemon started before Rust was installed keeps the old PATH and cannot find cargo.
./gradlew --stop >/dev/null 2>&1 || true
./gradlew "$task" -x lint

apk_dir="app/build/outputs/apk/$FLAVOR/$build_type_dir"
apk="$(ls -t "$apk_dir"/*.apk 2>/dev/null | head -1 || true)"
[ -n "$apk" ] || fail "Build finished but no APK found in $apk_dir"

mkdir -p "$OUT_DIR"
out="$OUT_DIR/WinNative-${FLAVOR}-${build_type_dir}-$(date +%Y%m%d-%H%M)-$(git rev-parse --short HEAD).apk"
cp "$apk" "$out"

# ---------------------------------------------------------------- install
if [ "$INSTALL" = 1 ]; then
	step "Installing on the connected device"
	command -v adb >/dev/null || fail "adb not found (sudo apt install adb). APK is at: $out"
	adb get-state >/dev/null 2>&1 || fail "No device connected over adb. APK is at: $out"
	adb install -r "$out"
fi

printf '\n\033[1;32mDone:\033[0m %s\n' "$out"
