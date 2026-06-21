#!/usr/bin/env bash
#
# Generates the SpendAI release signing keystore.
#
# Subject: CN=Deep, C=IN (everything else empty), per project convention.
#
# The keystore is git-ignored (*.jks in .gitignore) and must NEVER be
# committed. After generating it once, base64-encode the file and add it
# (plus the passwords + alias) as GitHub Actions secrets so CI can sign
# release builds:
#
#   SPENDAI_SIGNING_KEY_BASE64     -> base64 of spendai-release.jks
#   SPENDAI_SIGNING_STORE_PASSWORD -> the -storepass value below
#   SPENDAI_SIGNING_KEY_ALIAS      -> spendai
#   SPENDAI_SIGNING_KEY_PASSWORD    -> the -keypass value below
#
# Usage: ./scripts/generate-signing-key.sh [output-file]
#
set -euo pipefail

OUT="${1:-spendai-release.jks}"
STORE_PASS="${SPENDAI_SIGNING_STORE_PASSWORD:-spendaistore}"
KEY_PASS="${SPENDAI_SIGNING_KEY_PASSWORD:-spendaikey}"
ALIAS="${SPENDAI_SIGNING_KEY_ALIAS:-spendai}"
DNAME="CN=Deep, C=IN"   # name=Deep, country=IN, the rest intentionally blank.

if ! command -v keytool >/dev/null 2>&1; then
  echo "error: keytool not found. Install a JDK or run via the Android Studio bundled JDK." >&2
  exit 1
fi

echo "Generating ${OUT} (subject: ${DNAME}, alias: ${ALIAS})"
keytool -genkeypair -v \
  -keystore "${OUT}" \
  -storepass "${STORE_PASS}" \
  -keypass "${KEY_PASS}" \
  -alias "${ALIAS}" \
  -keyalg RSA -keysize 4096 -sigalg SHA256withRSA \
  -validity 10000 \
  -dname "${DNAME}"

echo
echo "Done. Now create the GitHub secrets:"
echo "  SPENDAI_SIGNING_KEY_BASE64=$(base64 -w 0 "${OUT}" | head -c 40)... (full output below)"
echo
echo "base64 (copy the WHOLE line into SPENDAI_SIGNING_KEY_BASE64):"
base64 -w 0 "${OUT}"; echo
echo
echo "SPENDAI_SIGNING_STORE_PASSWORD = ${STORE_PASS}"
echo "SPENDAI_SIGNING_KEY_ALIAS      = ${ALIAS}"
echo "SPENDAI_SIGNING_KEY_PASSWORD    = ${KEY_PASS}"
