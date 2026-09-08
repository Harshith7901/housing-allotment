#!/usr/bin/env bash
#
# Walks the public half of the API against a running instance and, at the end, verifies
# the published draw with the independent Python verifier.
#
#   ./scripts/walkthrough.sh [base-url] [draw-id]
#
# Defaults match the dev-profile demo scheme.

set -euo pipefail

BASE="${1:-http://localhost:8080}"
DRAW="${2:-DRAW-2026-01}"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

have() { command -v "$1" >/dev/null 2>&1; }
pretty() { if have jq; then jq "$@"; else cat; fi; }
rule() { printf '\n== %s %s\n' "$1" "$(printf '=%.0s' $(seq 1 $((70 - ${#1}))))"; }

rule "The draw, as the public sees it"
curl -sf "$BASE/api/public/draws/$DRAW" | pretty '{
  drawId, ruleSetVersion, rollHash, commitmentHex, publicEntropy, revealedNonce,
  seedHex, resultHash, candidates, selected, resultVerifiedByRecomputation
}'

rule "Check the authority did not choose the seed"
NONCE=$(curl -sf "$BASE/api/public/draws/$DRAW" | (have jq && jq -r .revealedNonce || sed -n 's/.*"revealedNonce":"\([^"]*\)".*/\1/p'))
COMMIT=$(curl -sf "$BASE/api/public/draws/$DRAW" | (have jq && jq -r .commitmentHex || sed -n 's/.*"commitmentHex":"\([^"]*\)".*/\1/p'))
RECOMPUTED=$(printf '%s' "commit/1|$NONCE" | sha256sum | cut -d' ' -f1)
echo "  published commitment : $COMMIT"
echo "  SHA256 of the nonce  : $RECOMPUTED"
if [ "$COMMIT" = "$RECOMPUTED" ]; then
  echo "  -> the nonce was fixed before the entropy value existed."
else
  echo "  -> MISMATCH: the nonce does not match the published commitment."
  exit 1
fi

rule "Check the result file hashes to the announced result hash"
curl -sf "$BASE/api/public/draws/$DRAW/result" -o "$WORK/result.txt"
echo "  announced : $(curl -sf "$BASE/api/public/draws/$DRAW" | (have jq && jq -r .resultHash || sed -n 's/.*"resultHash":"\([^"]*\)".*/\1/p'))"
echo "  computed  : $(sha256sum "$WORK/result.txt" | cut -d' ' -f1)"
echo "  lines     : $(grep -c '^sel=' "$WORK/result.txt") applicant outcomes"

rule "One applicant's own ticket, recomputed by hand"
APP=$(grep -m1 '^sel=' "$WORK/result.txt" | cut -d= -f2 | cut -d'|' -f1)
TICKET=$(grep -m1 "^sel=$APP|" "$WORK/result.txt" | cut -d'|' -f4)
SEED=$(curl -sf "$BASE/api/public/draws/$DRAW" | (have jq && jq -r .seedHex || sed -n 's/.*"seedHex":"\([^"]*\)".*/\1/p'))
echo "  application : $APP"
echo "  published   : $TICKET"
echo "  recomputed  : $(printf '%s' "ticket/1|$SEED|$APP" | sha256sum | cut -d' ' -f1)"

rule "What that applicant is told"
curl -sf "$BASE/api/public/applications/$APP/explanation" | pretty '{
  applicationId, status,
  draw: .draws[0] // null,
  howToVerify: .howToVerify.shellCommand
}'

rule "The audit head, meant to be quoted outside this system"
curl -sf "$BASE/api/public/audit/head" | pretty .

rule "Full independent verification"
curl -sfL "$BASE/api/public/draws/$DRAW/verification-bundle" -o "$WORK/bundle.zip"
if have unzip; then
  unzip -qo "$WORK/bundle.zip" -d "$WORK/bundle"
  if have python3; then
    python3 "$(dirname "$0")/../verify/verify.py" "$WORK/bundle" | tail -20
  else
    echo "  python3 not found; bundle unpacked at $WORK/bundle"
  fi
else
  echo "  unzip not found; bundle saved to $WORK/bundle.zip"
fi
