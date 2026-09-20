#!/bin/bash

# Fast liveness check against a running stack (make up). Answers one question: is the edge
# alive, can it issue and accept tokens, and does it accept an order?
#
#   make smoke
#
# Checks, in order:
#   1. api-gateway /actuator/health is UP
#   2. Keycloak issues a token for the demo user (correct scopes and audience)
#   3. an unauthenticated GET is rejected with 401
#   4. POST /api/v1/orders through the gateway returns 202 + Location
#
# It deliberately does NOT wait for a terminal saga state — that is demo.sh's job. Safe to
# re-run: each order uses a fresh Idempotency-Key.
#
# Exit code 0 = all checks passed, 1 = at least one failed.

set -uo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8090}"
ISSUER_URI="${JWT_ISSUER_URI:-http://localhost:8180/realms/order-platform}"
SKU="${DEMO_SKU:-SKU-1001}"
EXPECTED_AUDIENCE="${JWT_AUDIENCE:-order-platform}"

BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; RESET=$'\033[0m'
FAILURES=0

pass() { printf '%s PASS%s  %s\n' "$GREEN" "$RESET" "$*"; }
fail() { printf '%s FAIL%s  %s\n' "$RED" "$RESET" "$*"; FAILURES=$((FAILURES + 1)); }
dim()  { printf '%s       %s%s\n' "$DIM" "$*" "$RESET"; }

command -v curl > /dev/null 2>&1 || { echo "Missing required tool: curl" >&2; exit 1; }
command -v jq   > /dev/null 2>&1 || { echo "Missing required tool: jq" >&2; exit 1; }

new_uuid() {
    if command -v uuidgen > /dev/null 2>&1; then uuidgen; else cat /proc/sys/kernel/random/uuid; fi
}

printf '%s\n' "${BOLD}smoke test — ${GATEWAY_URL}${RESET}"

# 1. Gateway health -----------------------------------------------------------------------
HEALTH=$(curl -fsS --max-time 5 "${GATEWAY_URL}/actuator/health" 2>/dev/null)
if [ "$(printf '%s' "$HEALTH" | jq -r '.status' 2>/dev/null)" = "UP" ]; then
    pass "api-gateway health is UP"
else
    fail "api-gateway is not UP on ${GATEWAY_URL} (start it with: make up)"
    printf '\n%s SMOKE FAILED%s — the stack is not running.\n' "$RED$BOLD" "$RESET"
    exit 1
fi

# 2. Token issuance -----------------------------------------------------------------------
ACCESS_TOKEN=$(./scripts/get-token.sh 2>/dev/null)
if [ -n "${ACCESS_TOKEN:-}" ]; then
    CLAIMS=$(printf '%s' "$ACCESS_TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null)
    SCOPES=$(printf '%s' "$CLAIMS" | jq -r '.scope // empty')
    AUDIENCE=$(printf '%s' "$CLAIMS" | jq -r 'if (.aud | type) == "array" then .aud | join(",") else .aud end')
    pass "Keycloak issued a token for $(printf '%s' "$CLAIMS" | jq -r '.preferred_username')"
    dim "scopes: ${SCOPES}   audience: ${AUDIENCE}   issuer: $(printf '%s' "$CLAIMS" | jq -r '.iss')"
    case "$SCOPES" in *orders:write*) ;; *) fail "token is missing the orders:write scope" ;; esac
    case "$SCOPES" in *orders:read*)  ;; *) fail "token is missing the orders:read scope" ;; esac
    case "$AUDIENCE" in *"$EXPECTED_AUDIENCE"*) ;; *) fail "token audience does not contain ${EXPECTED_AUDIENCE}" ;; esac
else
    fail "could not obtain a token from ${ISSUER_URI}"
    printf '\n%s SMOKE FAILED%s — no token, nothing else can be checked.\n' "$RED$BOLD" "$RESET"
    exit 1
fi

# 3. Unauthenticated access is rejected ---------------------------------------------------
UNAUTH_STATUS=$(curl -s -o /dev/null -w '%{http_code}' --max-time 5 \
    "${GATEWAY_URL}/api/v1/orders/00000000-0000-0000-0000-000000000000" 2>/dev/null)
if [ "$UNAUTH_STATUS" = "401" ]; then
    pass "unauthenticated GET is rejected with 401"
else
    fail "unauthenticated GET returned ${UNAUTH_STATUS}, expected 401"
fi

# 4. One accepted order -------------------------------------------------------------------
IDEMPOTENCY_KEY=$(new_uuid)
BODY=$(jq -nc --arg sku "$SKU" '{lines: [{sku: $sku, quantity: 1}], currency: "USD", paymentInstrumentId: "pi_smoke_0001"}')
RESPONSE=$(curl -sS -D - -o /tmp/edmp-smoke-body.json -w '%{http_code}' \
    -X POST "${GATEWAY_URL}/api/v1/orders" \
    -H 'Content-Type: application/json' \
    -H "Authorization: Bearer ${ACCESS_TOKEN}" \
    -H "Idempotency-Key: ${IDEMPOTENCY_KEY}" \
    -d "$BODY" 2>/dev/null)
STATUS="${RESPONSE##*$'\n'}"
LOCATION=$(printf '%s' "$RESPONSE" | grep -i '^location:' | awk '{print $2}' | tr -d '\r')
CORRELATION_ID=$(printf '%s' "$RESPONSE" | grep -i '^x-correlation-id:' | awk '{print $2}' | tr -d '\r')

if [ "$STATUS" = "202" ] && [ -n "$LOCATION" ]; then
    pass "POST /api/v1/orders returned 202 with Location ${LOCATION}"
    dim "orderId: $(jq -r '.orderId' /tmp/edmp-smoke-body.json)   correlationId: ${CORRELATION_ID:-<none>}"
else
    fail "POST /api/v1/orders returned ${STATUS} (expected 202)"
    jq -c . /tmp/edmp-smoke-body.json 2>/dev/null | sed 's/^/        /'
fi

# Result ----------------------------------------------------------------------------------
echo
if [ "$FAILURES" -eq 0 ]; then
    printf '%s SMOKE PASSED%s — edge is alive, tokens work, orders are accepted.\n' "$GREEN$BOLD" "$RESET"
    printf '%s              run `make demo` for the full saga (CONFIRMED + compensation).%s\n' "$DIM" "$RESET"
    exit 0
fi
printf '%s SMOKE FAILED%s — %s check(s) failed. Inspect with: make logs\n' "$RED$BOLD" "$RESET" "$FAILURES"
exit 1
