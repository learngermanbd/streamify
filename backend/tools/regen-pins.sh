#!/usr/bin/env bash
# regen-pins.sh — Regenerate OkHttp + Android-network-security pins
#                 from the live cert at learngermanwith.fun:443.
#
# Use after every cert rotation. Outputs the SPKI SHA-256 hashes in
# the formats needed by:
#   - app/src/main/res/xml/network_security_config.xml
#   - app/src/main/java/com/streamify/app/security/SSLPinner.kt
#
# Usage:
#   ./regen-pins.sh                                # default host
#   ./regen-pins.sh other.example.com              # custom host
#   HOST=other.example.com PORT=443 ./regen-pins.sh
#
# Exit codes:
#   0  pins fetched and emitted
#   1  openssl s_client failed (host unreachable / TLS handshake)
#   2  leaf or intermediate cert missing in chain

set -euo pipefail

HOST=${1:-${HOST:-learngermanwith.fun}}
PORT=${PORT:-443}

echo "[regen-pins] fetching ${HOST}:${PORT} ..." >&2

# -showcerts prints the full chain; the leaf is the first cert, the
# first intermediate is the second. We split into separate PEMs.
TMP=$(mktemp)
trap "rm -f $TMP $TMP.leaf $TMP.int" EXIT
echo "" | openssl s_client -connect "${HOST}:${PORT}" -servername "${HOST}" -showcerts > "$TMP" 2>/dev/null

# Grab leaf (everything up to and including the FIRST -----END CERTIFICATE-----).
awk '/-----BEGIN CERTIFICATE-----/{n++} {print} n==1 && /-----END CERTIFICATE-----/{exit}' "$TMP" > "$TMP.leaf"
# Grab first intermediate (BEGIN #2 up to its END). If the chain
# has no intermediate, this file stays empty and we warn loudly.
awk 'BEGIN{n=0} /-----BEGIN CERTIFICATE-----/{n++} n==2{print} n==2 && /-----END CERTIFICATE-----/{exit}' "$TMP" > "$TMP.int"

if ! grep -q "BEGIN CERTIFICATE" "$TMP.leaf"; then
  echo "[regen-pins] BLAME: no leaf cert in chain" >&2
  exit 2
fi

# SPKI SHA-256 pin (the OkHttp/Android convention).
pin_of() {
  openssl x509 -pubkey -noout < "$1" 2>/dev/null \
    | openssl pkey -pubin -outform DER 2>/dev/null \
    | openssl dgst -sha256 -binary \
    | openssl base64
}

LEAF_PIN=$(pin_of "$TMP.leaf")
# Capture pin_of failure explicitly so set -e doesn't abort before
# we can warn the user that the backup pin is unavailable.
INT_PIN=""
if [ -s "$TMP.int" ] && grep -q "BEGIN CERTIFICATE" "$TMP.int"; then
  if INT_PIN=$(pin_of "$TMP.int" 2>/dev/null); then
    :
  else
    INT_PIN=""
  fi
fi

if [ -z "$INT_PIN" ]; then
  echo "" >&2
  echo "[regen-pins] WARN: cert chain has no usable intermediate - backup pin unavailable." >&2
  echo "[regen-pins]       Leaf expiry will require a coordinated app update." >&2
fi

echo "" >&2
echo "=== Live leaf @ ${HOST}:${PORT} ===" >&2
openssl x509 -subject -issuer -dates -fingerprint -sha256 < "$TMP.leaf" >&2
if [ -n "$INT_PIN" ]; then
  echo "" >&2
  echo "=== First intermediate cert ===" >&2
  openssl x509 -subject -issuer < "$TMP.int" >&2
fi

# ── SSLPinner.kt snippet ──────────────────────────────────────────
cat <<EOF

=== Pins for SSLPinner.kt (OkHttp CertificatePinner) ===
    // leaf (primary, replace PRIMARY_PIN below)
    "sha256/${LEAF_PIN}",
EOF

if [ -n "$INT_PIN" ]; then
  cat <<EOF
    // backup (intermediate, survives leaf renewal)
    "sha256/${INT_PIN}",
EOF
else
  cat <<EOF
    // (no intermediate in chain - backup pin unavailable; rotate after leaf renewal)
EOF
fi

# ── network_security_config.xml snippet ───────────────────────────
cat <<EOF

=== Pins for res/xml/network_security_config.xml ===
            <pin digest="SHA-256">${LEAF_PIN}</pin>
EOF

if [ -n "$INT_PIN" ]; then
  cat <<EOF
            <!-- backup: intermediate -->
            <pin digest="SHA-256">${INT_PIN}</pin>
EOF
else
  cat <<EOF
            <!-- (no intermediate in chain; backup pin unavailable) -->
EOF
fi

cat <<EOF

Replace the corresponding lines in:
  app/src/main/res/xml/network_security_config.xml
  app/src/main/java/com/streamify/app/security/SSLPinner.kt
then rebuild the APK.
EOF
