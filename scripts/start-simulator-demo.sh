#!/usr/bin/env bash
set -Eeuo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_DIR="$ROOT_DIR/examples/simulator-app"
APP_ID="com.neptunekit.sdk.android.examples.simulator"
APP_ACTIVITY=".MainActivity"
CALLBACK_HOST_PORT=28766
CALLBACK_DEVICE_PORT=18766
BOOT_TIMEOUT_SECONDS=600
AVD_NAME=""
LIST_AVDS=0
DRY_RUN=0
WINDOW_MODE="headless"
EXTRA_EMULATOR_ARGS=()

die() {
  echo "error: $*" >&2
  exit 1
}

warn() {
  echo "warning: $*" >&2
}

info() {
  echo "==> $*" >&2
}

usage() {
  cat <<'EOF'
Usage:
  scripts/start-simulator-demo.sh [--avd NAME] [--list-avds] [--dry-run] [--window]

Options:
  --avd NAME      Use a specific Android Virtual Device.
  --list-avds     Print available AVD names and exit.
  --dry-run       Resolve SDK, AVD, adb, and planned commands without launching anything.
  --window        Launch the emulator with a visible window instead of headless mode.
  --help          Show this help text.

Environment:
  ANDROID_SDK_ROOT / ANDROID_HOME
  AVD_NAME
  BOOT_TIMEOUT_SECONDS
EOF
}

while (($# > 0)); do
  case "$1" in
    --avd)
      shift || die "Missing value after --avd"
      [ $# -gt 0 ] || die "Missing value after --avd"
      AVD_NAME="$1"
      ;;
    --list-avds)
      LIST_AVDS=1
      ;;
    --dry-run)
      DRY_RUN=1
      ;;
    --window)
      WINDOW_MODE="window"
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      die "Unknown argument: $1"
      ;;
  esac
  shift
done

local_sdk_dir() {
  local local_properties="$APP_DIR/local.properties"
  [ -f "$local_properties" ] || return 0
  sed -n 's/^sdk\.dir=//p' "$local_properties" | tail -n 1
}

is_valid_sdk_root() {
  local sdk_root="$1"
  [ -n "$sdk_root" ] || return 1
  [ -x "$sdk_root/emulator/emulator" ] || return 1
  [ -d "$sdk_root/platform-tools" ] || return 1
}

resolve_sdk_root() {
  local candidate
  for candidate in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$(local_sdk_dir)"; do
    if is_valid_sdk_root "$candidate"; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

SDK_ROOT="$(resolve_sdk_root || true)"
if [ -z "$SDK_ROOT" ]; then
  cat >&2 <<EOF
error: unable to resolve a valid Android SDK root.

Checked:
  - ANDROID_SDK_ROOT=${ANDROID_SDK_ROOT:-<unset>}
  - ANDROID_HOME=${ANDROID_HOME:-<unset>}
  - $APP_DIR/local.properties sdk.dir=$(local_sdk_dir 2>/dev/null || true)

Expected a directory containing:
  - emulator/emulator
  - platform-tools/adb

If the SDK is missing, install the official tools with:
  sdkmanager --install emulator platform-tools
EOF
  exit 1
fi

EMULATOR_BIN="$SDK_ROOT/emulator/emulator"
ADB_BIN="$SDK_ROOT/platform-tools/adb"
if [ ! -x "$ADB_BIN" ]; then
  if command -v adb >/dev/null 2>&1; then
    ADB_BIN="$(command -v adb)"
  else
    die "Unable to find adb. Checked $SDK_ROOT/platform-tools/adb and PATH."
  fi
fi

list_avds() {
  "$EMULATOR_BIN" -list-avds 2>/dev/null | sed '/^$/d'
}

format_avd_table() {
  local index=1
  while IFS= read -r avd; do
    [ -n "$avd" ] || continue
    printf '  [%d] %s\n' "$index" "$avd"
    index=$((index + 1))
  done < <(list_avds)
}

choose_avd() {
  local avds=()
  local avd
  while IFS= read -r avd; do
    [ -n "$avd" ] || continue
    avds+=("$avd")
  done < <(list_avds)

  if [ "${#avds[@]}" -eq 0 ]; then
    cat >&2 <<EOF
error: no Android Virtual Devices were found.
Available AVDs: <none>

Create one with Android Studio Device Manager, or use:
  sdkmanager --install "system-images;android-34;google_apis;arm64-v8a"
  avdmanager create avd -n Neptune_API_34 -k "system-images;android-34;google_apis;arm64-v8a" -d pixel_7
EOF
    exit 1
  fi

  if [ -n "$AVD_NAME" ]; then
    local candidate
    for candidate in "${avds[@]}"; do
      if [ "$candidate" = "$AVD_NAME" ]; then
        printf '%s\n' "$AVD_NAME"
        return 0
      fi
    done
    local avd_table
    avd_table="$(format_avd_table)"
    cat >&2 <<EOF
error: requested AVD "$AVD_NAME" was not found.
Available AVDs:
$avd_table
EOF
    exit 1
  fi

  if [ "${#avds[@]}" -eq 1 ]; then
    printf '%s\n' "${avds[0]}"
    return 0
  fi

  if [ -t 0 ] && [ -t 1 ]; then
    info "Multiple AVDs detected:"
    local i=0
    while [ "$i" -lt "${#avds[@]}" ]; do
      printf '  [%d] %s\n' "$((i + 1))" "${avds[$i]}" >&2
      i=$((i + 1))
    done
    while true; do
      printf 'Select AVD number: ' >&2
      read -r choice || exit 1
      case "$choice" in
        ''|*[!0-9]*)
          warn "Enter a numeric choice."
          ;;
        *)
          if [ "$choice" -ge 1 ] && [ "$choice" -le "${#avds[@]}" ]; then
            printf '%s\n' "${avds[$((choice - 1))]}"
            return 0
          fi
          warn "Choice out of range."
          ;;
      esac
    done
  fi

    cat >&2 <<EOF
error: multiple AVDs are available, but no selection was provided.
Use --avd NAME or set AVD_NAME=NAME.
Available AVDs:
$(format_avd_table)
EOF
  exit 1
}

choose_console_port() {
  local port=5554
  local serial
  local adb_port
  while [ "$port" -le 5680 ]; do
    adb_port=$((port + 1))
    if command -v lsof >/dev/null 2>&1; then
      if lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1 || \
         lsof -nP -iTCP:"$adb_port" -sTCP:LISTEN >/dev/null 2>&1; then
        port=$((port + 2))
        continue
      fi
    fi

    serial="emulator-$port"
    if ! "$ADB_BIN" devices 2>/dev/null | awk -v serial="$serial" '$1 == serial {found=1} END {exit found ? 0 : 1}'; then
      printf '%s\n' "$port"
      return 0
    fi
    port=$((port + 2))
  done
  die "Unable to find a free emulator console port."
}

wait_for_adb_online() {
  local serial="$1"
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  local state=""

  while [ "$SECONDS" -lt "$deadline" ]; do
    state="$("$ADB_BIN" devices 2>/dev/null | awk -v serial="$serial" '$1 == serial {print $2; exit}')"
    if [ "$state" = "device" ]; then
      return 0
    fi
    sleep 2
  done

  return 1
}

wait_for_boot_complete() {
  local serial="$1"
  local deadline=$((SECONDS + BOOT_TIMEOUT_SECONDS))
  local boot_completed=""

  while [ "$SECONDS" -lt "$deadline" ]; do
    boot_completed="$("$ADB_BIN" -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')"
    if [ "$boot_completed" = "1" ]; then
      return 0
    fi
    sleep 5
  done

  return 1
}

if [ "$LIST_AVDS" -eq 1 ]; then
  info "Resolved SDK root: $SDK_ROOT"
  info "Available AVDs:"
  format_avd_table
  exit 0
fi

AVD_TO_RUN="$(choose_avd)"
CONSOLE_PORT="$(choose_console_port)"
ADB_SERIAL="emulator-$CONSOLE_PORT"

if [ "$DRY_RUN" -eq 1 ]; then
  info "Resolved SDK root: $SDK_ROOT"
  info "Resolved emulator: $EMULATOR_BIN"
  info "Resolved adb: $ADB_BIN"
  info "Selected AVD: $AVD_TO_RUN"
  info "Selected adb serial: $ADB_SERIAL"
  info "Launch mode: $WINDOW_MODE"
  info "Would install demo app from: $APP_DIR"
  info "Would run: $EMULATOR_BIN -avd $AVD_TO_RUN -port $CONSOLE_PORT ..."
  info "Would run: (cd $APP_DIR && ANDROID_SDK_ROOT=$SDK_ROOT ANDROID_HOME=$SDK_ROOT ./gradlew --no-daemon :app:installDebug)"
  info "Would run: $ADB_BIN -s $ADB_SERIAL shell am start -W -n $APP_ID/$APP_ACTIVITY"
  exit 0
fi

EMULATOR_LOG="$(mktemp -t neptune-emulator)"
cleanup() {
  local exit_code=$?
  if [ "$exit_code" -ne 0 ]; then
    warn "Emulator log: $EMULATOR_LOG"
  else
    rm -f "$EMULATOR_LOG"
  fi
}
trap cleanup EXIT

EMULATOR_ARGS=(
  -avd "$AVD_TO_RUN"
  -port "$CONSOLE_PORT"
  -no-boot-anim
  -no-snapshot-save
  -no-snapshot-load
  -no-audio
)

if [ "$WINDOW_MODE" = "headless" ]; then
  EMULATOR_ARGS+=(-no-window -gpu swiftshader_indirect)
fi

if [ "${#EXTRA_EMULATOR_ARGS[@]}" -gt 0 ]; then
  EMULATOR_ARGS+=("${EXTRA_EMULATOR_ARGS[@]}")
fi

info "Resolved SDK root: $SDK_ROOT"
info "Starting emulator for AVD: $AVD_TO_RUN"
info "ADB serial will be: $ADB_SERIAL"
nohup "$EMULATOR_BIN" "${EMULATOR_ARGS[@]}" >"$EMULATOR_LOG" 2>&1 < /dev/null &
EMULATOR_PID=$!

if ! wait_for_adb_online "$ADB_SERIAL"; then
  if kill -0 "$EMULATOR_PID" >/dev/null 2>&1; then
    warn "Emulator process is still running, but adb did not reach online state before timeout."
  else
    warn "Emulator process exited before adb became online."
  fi
  warn "Emulator log: $EMULATOR_LOG"
  tail -n 60 "$EMULATOR_LOG" >&2 || true
  exit 1
fi

info "Emulator is online. Waiting for boot completion..."
if ! wait_for_boot_complete "$ADB_SERIAL"; then
  warn "adb did not report sys.boot_completed=1 before timeout."
  warn "Emulator log: $EMULATOR_LOG"
  tail -n 60 "$EMULATOR_LOG" >&2 || true
  exit 1
fi

info "Installing demo app via Gradle."
(
  cd "$APP_DIR"
  ANDROID_SDK_ROOT="$SDK_ROOT" ANDROID_HOME="$SDK_ROOT" ./gradlew --no-daemon :app:installDebug
)

info "Creating adb forward tcp:${CALLBACK_HOST_PORT} -> tcp:${CALLBACK_DEVICE_PORT}."
"$ADB_BIN" -s "$ADB_SERIAL" forward "tcp:${CALLBACK_HOST_PORT}" "tcp:${CALLBACK_DEVICE_PORT}"

info "Launching demo activity."
"$ADB_BIN" -s "$ADB_SERIAL" shell am start -W -n "$APP_ID/$APP_ACTIVITY"

info "Demo app started successfully on $ADB_SERIAL."
