#!/usr/bin/env bash

set -euo pipefail

: "${GITLEAKS_VERSION:?GITLEAKS_VERSION is required}"
: "${GITLEAKS_SHA256:?GITLEAKS_SHA256 is required}"

if [[ ! "$GITLEAKS_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "GITLEAKS_VERSION must be a pinned semantic version" >&2
  exit 2
fi
if [[ ! "$GITLEAKS_SHA256" =~ ^[0-9a-f]{64}$ ]]; then
  echo "GITLEAKS_SHA256 must be a lowercase SHA-256 checksum" >&2
  exit 2
fi

archive_name="gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
download_url="${GITLEAKS_DOWNLOAD_URL:-https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/${archive_name}}"
install_dir="${GITLEAKS_INSTALL_DIR:-/usr/local/bin}"
attempts="${GITLEAKS_RETRY_ATTEMPTS:-3}"
retry_delay="${GITLEAKS_RETRY_DELAY_SECONDS:-2}"

if [[ ! "$attempts" =~ ^[1-9][0-9]*$ ]]; then
  echo "GITLEAKS_RETRY_ATTEMPTS must be a positive integer" >&2
  exit 2
fi
if [[ ! "$retry_delay" =~ ^[0-9]+$ ]]; then
  echo "GITLEAKS_RETRY_DELAY_SECONDS must be a non-negative integer" >&2
  exit 2
fi

work_dir="$(mktemp -d)"
trap 'rm -rf "$work_dir"' EXIT
archive_path="$work_dir/$archive_name"

downloaded=false
for ((attempt = 1; attempt <= attempts; attempt++)); do
  if curl --fail --location --silent --show-error \
    --connect-timeout 10 --max-time 60 \
    "$download_url" --output "$archive_path"; then
    downloaded=true
    break
  fi
  rm -f "$archive_path"
  if (( attempt < attempts )); then
    sleep "$retry_delay"
  fi
done

if [[ "$downloaded" != true ]]; then
  echo "gitleaks download failed after $attempts attempts: $download_url" >&2
  exit 1
fi

if sha256sum --help 2>&1 | grep -q -- '--check'; then
  printf '%s  %s\n' "$GITLEAKS_SHA256" "$archive_path" | sha256sum --check --strict
else
  actual_sha256="$(shasum -a 256 "$archive_path" | awk '{print $1}')"
  if [[ "$actual_sha256" != "$GITLEAKS_SHA256" ]]; then
    echo "gitleaks checksum mismatch: expected $GITLEAKS_SHA256, got $actual_sha256" >&2
    exit 1
  fi
fi
tar --extract --gzip --file "$archive_path" --directory "$work_dir" gitleaks

mkdir -p "$install_dir"
install_path="$install_dir/gitleaks"
if [[ -w "$install_dir" ]]; then
  install -m 0755 "$work_dir/gitleaks" "$install_path"
else
  sudo install -m 0755 "$work_dir/gitleaks" "$install_path"
fi

"$install_path" version
