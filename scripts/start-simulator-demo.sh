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
RESIDENCY_SECONDS=30
KEEPALIVE_SECONDS=0
KEEPALIVE_INTERVAL_SECONDS=5
AVD_NAME=""
LIST_AVDS=0
DRY_RUN=0
WINDOW_MODE="window"
KEEP_EMULATOR_LOG=0
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
  scripts/start-simulator-demo.sh [--avd NAME] [--list-avds] [--dry-run] [--window|--headless] [--residency-seconds N] [--keep-emulator-log]

Options:
  --avd NAME      Use a specific Android Virtual Device.
  --list-avds     Print available AVD names and exit.
  --dry-run       Resolve SDK, AVD, adb, and planned commands without launching anything.
  --window        Launch the emulator with a visible window (default).
  --headless      Launch the emulator in headless mode.
  --residency-seconds N
                  Poll emulator residency with the resolved adb for N seconds after app launch. Use 0 to disable. Default: 30.
  --keepalive-seconds N
                  Keep the script alive for N seconds after startup and continuously self-heal adb forward.
                  Use 0 to disable. Default: 0.
  --keep-emulator-log
                  Keep emulator log file even on successful run.
  --help          Show this help text.

Environment:
  ANDROID_SDK_ROOT / ANDROID_HOME
  AVD_NAME
  BOOT_TIMEOUT_SECONDS
  RESIDENCY_SECONDS
  KEEPALIVE_SECONDS
  KEEP_EMULATOR_LOG
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
    --headless)
      WINDOW_MODE="headless"
      ;;
    --residency-seconds)
      shift || die "Missing value after --residency-seconds"
      [ $# -gt 0 ] || die "Missing value after --residency-seconds"
      RESIDENCY_SECONDS="$1"
      ;;
    --keep-emulator-log)
      KEEP_EMULATOR_LOG=1
      ;;
    --keepalive-seconds)
      shift || die "Missing value after --keepalive-seconds"
      [ $# -gt 0 ] || die "Missing value after --keepalive-seconds"
      KEEPALIVE_SECONDS="$1"
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

RESIDENCY_SECONDS="${RESIDENCY_SECONDS:-30}"
KEEPALIVE_SECONDS="${KEEPALIVE_SECONDS:-0}"
KEEP_EMULATOR_LOG="${KEEP_EMULATOR_LOG:-0}"

case "$RESIDENCY_SECONDS" in
  ''|*[!0-9]*)
    die "RESIDENCY_SECONDS must be a non-negative integer, got: $RESIDENCY_SECONDS"
    ;;
esac

case "$KEEP_EMULATOR_LOG" in
  0|1) ;;
  *)
    die "KEEP_EMULATOR_LOG must be 0 or 1, got: $KEEP_EMULATOR_LOG"
    ;;
esac

case "$KEEPALIVE_SECONDS" in
  ''|*[!0-9]*)
    die "KEEPALIVE_SECONDS must be a non-negative integer, got: $KEEPALIVE_SECONDS"
    ;;
esac

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

pin_adb_path() {
  local adb_dir
  adb_dir="$(cd "$(dirname "$ADB_BIN")" && pwd)"
  case ":$PATH:" in
    *":$adb_dir:"*) ;;
    *)
      export PATH="$adb_dir:$PATH"
      ;;
  esac
  hash -r 2>/dev/null || true
}

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

warn_if_adb_mismatch() {
  local path_adb=""
  path_adb="$(command -v adb 2>/dev/null || true)"
  if [ -z "$path_adb" ]; then
    return 0
  fi

  if [ "$path_adb" = "$ADB_BIN" ]; then
    return 0
  fi

  local resolved_version=""
  local path_version=""
  resolved_version="$("$ADB_BIN" version 2>/dev/null | sed -n '2p' || true)"
  path_version="$(adb version 2>/dev/null | sed -n '2p' || true)"
  warn "PATH adb differs from resolved adb."
  warn "Resolved adb: $ADB_BIN (${resolved_version:-unknown version})"
  warn "PATH adb: $path_adb (${path_version:-unknown version})"
  warn "Use '$ADB_BIN devices -l' when checking residency for consistent results."
}

forward_exists() {
  local serial="$1"
  "$ADB_BIN" forward --list 2>/dev/null | awk -v serial="$serial" -v host="tcp:${CALLBACK_HOST_PORT}" -v device="tcp:${CALLBACK_DEVICE_PORT}" '
    $1 == serial && $2 == host && $3 == device { found=1 }
    END { exit found ? 0 : 1 }
  '
}

ensure_forward() {
  local serial="$1"
  if forward_exists "$serial"; then
    return 0
  fi

  warn "adb forward missing for $serial, rebuilding tcp:${CALLBACK_HOST_PORT}->tcp:${CALLBACK_DEVICE_PORT}."
  "$ADB_BIN" -s "$serial" forward "tcp:${CALLBACK_HOST_PORT}" "tcp:${CALLBACK_DEVICE_PORT}"
}

verify_residency() {
  local serial="$1"
  local pid="$2"
  local seconds="$3"
  local interval=5
  local polls=1
  local i=1
  local state=""
  local line=""

  if [ "$seconds" -le 0 ]; then
    info "Residency check disabled (RESIDENCY_SECONDS=$seconds)."
    return 0
  fi

  polls=$((seconds / interval))
  if [ "$polls" -lt 1 ]; then
    polls=1
  fi

  info "Verifying emulator residency for ${seconds}s using $ADB_BIN."
  while [ "$i" -le "$polls" ]; do
    state="$("$ADB_BIN" -s "$serial" get-state 2>/dev/null || true)"
    line="$("$ADB_BIN" devices 2>/dev/null | awk -v serial="$serial" '$1 == serial {print $0; exit}')"
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      warn "Residency check failed: emulator process exited during poll $i/$polls."
      return 1
    fi
    if [ "$state" != "device" ]; then
      warn "Residency check failed: adb state is '${state:-<empty>}' during poll $i/$polls."
      warn "adb devices line: ${line:-<missing>}"
      return 1
    fi
    ensure_forward "$serial"
    sleep "$interval"
    i=$((i + 1))
  done

  info "Emulator residency verified for ${seconds}s."
}

keepalive_monitor() {
  local serial="$1"
  local pid="$2"
  local seconds="$3"
  local interval="$KEEPALIVE_INTERVAL_SECONDS"
  local polls=1
  local i=1
  local state=""

  if [ "$seconds" -le 0 ]; then
    info "Keepalive monitor disabled (KEEPALIVE_SECONDS=$seconds)."
    return 0
  fi

  polls=$((seconds / interval))
  if [ "$polls" -lt 1 ]; then
    polls=1
  fi

  info "Keepalive monitor running for ${seconds}s (interval ${interval}s)."
  while [ "$i" -le "$polls" ]; do
    if ! kill -0 "$pid" >/dev/null 2>&1; then
      warn "Keepalive failed: emulator process exited during poll $i/$polls."
      return 1
    fi

    state="$("$ADB_BIN" -s "$serial" get-state 2>/dev/null || true)"
    if [ "$state" != "device" ]; then
      warn "Keepalive detected adb state '${state:-<empty>}' on poll $i/$polls, waiting for reconnection."
      if ! wait_for_adb_online "$serial"; then
        warn "Keepalive failed: adb did not recover before timeout."
        return 1
      fi
    fi

    ensure_forward "$serial"
    sleep "$interval"
    i=$((i + 1))
  done

  info "Keepalive monitor completed."
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
  info "Residency check seconds: $RESIDENCY_SECONDS"
  info "Keepalive monitor seconds: $KEEPALIVE_SECONDS"
  info "Keep emulator log on success: $KEEP_EMULATOR_LOG"
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
  elif [ "$KEEP_EMULATOR_LOG" -eq 1 ]; then
    info "Keeping emulator log: $EMULATOR_LOG"
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

if [ "${#EXTRA_EMULATOR_ARGS[@]}" -gt 0 ]; then
  EMULATOR_ARGS+=("${EXTRA_EMULATOR_ARGS[@]}")
fi

if [ "$WINDOW_MODE" = "headless" ]; then
  EMULATOR_ARGS+=(-no-window -gpu swiftshader_indirect)
fi

info "Resolved SDK root: $SDK_ROOT"
info "Resolved adb: $ADB_BIN"
pin_adb_path
warn_if_adb_mismatch
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

verify_residency "$ADB_SERIAL" "$EMULATOR_PID" "$RESIDENCY_SECONDS"
keepalive_monitor "$ADB_SERIAL" "$EMULATOR_PID" "$KEEPALIVE_SECONDS"

info "Demo app started successfully on $ADB_SERIAL."
