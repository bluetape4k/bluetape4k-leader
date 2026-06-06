#!/usr/bin/env bash
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "Usage: retry-snapshot-warmup.sh <gradle-args...>" >&2
  exit 64
fi

max_attempts="${SNAPSHOT_WARMUP_ATTEMPTS:-5}"
delay_seconds="${SNAPSHOT_WARMUP_DELAY_SECONDS:-30}"

is_snapshot_metadata_failure() {
  local log_file="$1"
  grep -Eq \
    "central\.sonatype\.com/repository/maven-snapshots|Unable to load Maven meta-data|Received status code 403" \
    "$log_file"
}

for attempt in $(seq 1 "$max_attempts"); do
  log_file="$(mktemp)"

  set +e
  ./gradlew "$@" 2>&1 | tee "$log_file"
  status="${PIPESTATUS[0]}"
  set -e

  if [ "$status" -eq 0 ]; then
    rm -f "$log_file"
    exit 0
  fi

  if [ "$attempt" -lt "$max_attempts" ] && is_snapshot_metadata_failure "$log_file"; then
    echo "SNAPSHOT metadata resolution failed; retrying warm-up attempt $((attempt + 1))/${max_attempts} after ${delay_seconds}s"
    rm -f "$log_file"
    sleep "$delay_seconds"
    continue
  fi

  rm -f "$log_file"
  exit "$status"
done
