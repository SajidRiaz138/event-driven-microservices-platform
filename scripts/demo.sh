#!/bin/bash

# Scripted end-to-end demo of the order saga, through the gateway, against the running
# compose stack (make up).
#
#   make up && make demo
#
# It proves two things, both across real services over real Kafka and Postgres:
#
#   1. HAPPY PATH      POST /api/v1/orders -> 202 -> ... -> status CONFIRMED
#                      (reserve stock -> authorize payment -> capture -> confirm)
#   2. COMPENSATION    the same call with a payment instrument the provider declines
#                      -> 202 -> ... -> status CANCELLED, reason PAYMENT_DECLINED
#                      (stock was reserved, then released again)
#
# Every call carries a real Keycloak bearer token (ADR-0009: identity is the `sub` claim,
# never a caller-supplied header) and an Idempotency-Key (ADR-0005), and goes through
# api-gateway on the host port — the services themselves are not published.
#
# Re-running is safe: each order gets a fresh Idempotency-Key, and the demo SKU is seeded
# with 100 units, so the stack tolerates many runs before stock matters.
#
# Exit code 0 = both scenarios passed, 1 = something failed.

set -uo pipefail

GATEWAY_URL="${GATEWAY_URL:-http://localhost:8090}"
ISSUER_URI="${JWT_ISSUER_URI:-http://localhost:8180/realms/order-platform}"
SKU="${DEMO_SKU:-SKU-1001}"
QUANTITY="${DEMO_QUANTITY:-2}"
POLL_TIMEOUT_SECONDS="${POLL_TIMEOUT_SECONDS:-60}"
COMPOSE_FILE="${COMPOSE_FILE:-deploy/local/compose.yaml}"

BOLD=$'\033[1m'; GREEN=$'\033[32m'; RED=$'\033[31m'; DIM=$'\033[2m'; RESET=$'\033[0m'

FAILURES=0

info()  { printf '%s\n' "$*"; }
step()  { printf '\n%s==> %s%s\n' "$BOLD" "$*" "$RESET"; }
pass()  { printf '%s  PASS%s  %s\n' "$GREEN" "$RESET" "$*"; }
fail()  { printf '%s  FAIL%s  %s\n' "$RED" "$RESET" "$*"; FAILURES=$((FAILURES + 1)); }
dim()   { printf '%s%s%s\n' "$DIM" "$*" "$RESET"; }

require() {
    command -v "$1" > /dev/null 2>&1 || { echo "Missing required tool: $1" >&2; exit 1; }
}

new_uuid() {
    if command -v uuidgen > /dev/null 2>&1; then uuidgen; else cat /proc/sys/kernel/random/uuid; fi
}

require curl
require jq

printf '%s\n' "${BOLD}Event-Driven Order Platform — end-to-end demo${RESET}"
dim "gateway: ${GATEWAY_URL}   keycloak: ${ISSUER_URI}   sku: ${SKU} x${QUANTITY}"

# ---------------------------------------------------------------------------------------
# 0. The stack must be up. We check the gateway only: it depends on order-service being
#    healthy, which in turn depends on Postgres, Kafka and Keycloak being healthy.
# ---------------------------------------------------------------------------------------
step "Checking the stack is up"
if ! curl -fsS --max-time 5 "${GATEWAY_URL}/actuator/health" > /dev/null 2>&1; then
    fail "api-gateway is not answering on ${GATEWAY_URL}"
    info ""
    info "Start the stack first:"
    info "    make up          # docker compose -f ${COMPOSE_FILE} up -d --build --wait"
    info "    make demo"
    exit 1
fi
pass "api-gateway is up ($(curl -fsS "${GATEWAY_URL}/actuator/health" | jq -r '.status'))"

# ---------------------------------------------------------------------------------------
# 1. A real token for the demo customer (orders:write + orders:read come from the realm's
#    default client scopes).
# ---------------------------------------------------------------------------------------
step "Getting an access token from Keycloak"
ACCESS_TOKEN=$(./scripts/get-token.sh) || { fail "could not obtain a token"; exit 1; }
CLAIMS=$(printf '%s' "$ACCESS_TOKEN" | cut -d. -f2 | tr '_-' '/+' | base64 -d 2>/dev/null)
pass "token for $(printf '%s' "$CLAIMS" | jq -r '.preferred_username') (sub $(printf '%s' "$CLAIMS" | jq -r '.sub'))"
dim  "scopes: $(printf '%s' "$CLAIMS" | jq -r '.scope')   audience: $(printf '%s' "$CLAIMS" | jq -r '.aud')"

# ---------------------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------------------

# place_order <paymentInstrumentId> -> sets ORDER_ID and CORRELATION_ID, returns non-zero
# unless the gateway answered 202 Accepted.
place_order() {
    local instrument="$1"
    local idempotency_key body response status
    idempotency_key=$(new_uuid)
    body=$(jq -nc --arg sku "$SKU" --argjson qty "$QUANTITY" --arg pi "$instrument" \
        '{lines: [{sku: $sku, quantity: $qty}], currency: "USD", paymentInstrumentId: $pi}')

    response=$(curl -sS -D - -o /tmp/edmp-demo-body.json -w '%{http_code}' \
        -X POST "${GATEWAY_URL}/api/v1/orders" \
        -H 'Content-Type: application/json' \
        -H "Authorization: Bearer ${ACCESS_TOKEN}" \
        -H "Idempotency-Key: ${idempotency_key}" \
        -d "$body" 2>/dev/null)
    status="${response##*$'\n'}"

    LOCATION=$(printf '%s' "$response" | grep -i '^location:' | awk '{print $2}' | tr -d '\r')
    CORRELATION_ID=$(printf '%s' "$response" | grep -i '^x-correlation-id:' | awk '{print $2}' | tr -d '\r')
    ORDER_ID=$(jq -r '.orderId // empty' /tmp/edmp-demo-body.json 2>/dev/null)

    dim "request  : POST /api/v1/orders  Idempotency-Key: ${idempotency_key}"
    dim "           $(printf '%s' "$body")"
    dim "response : HTTP ${status}  Location: ${LOCATION:-<none>}"

    [ "$status" = "202" ] && [ -n "$ORDER_ID" ]
}

# poll_until_terminal <orderId> -> echoes the final "STATUS reason" pair once the order is
# no longer PENDING, or gives up after POLL_TIMEOUT_SECONDS.
poll_until_terminal() {
    local order_id="$1" deadline=$((SECONDS + POLL_TIMEOUT_SECONDS)) status reason
    while [ $SECONDS -lt $deadline ]; do
        if curl -fsS --max-time 5 "${GATEWAY_URL}/api/v1/orders/${order_id}" \
                -H "Authorization: Bearer ${ACCESS_TOKEN}" -o /tmp/edmp-demo-order.json 2>/dev/null; then
            status=$(jq -r '.status // empty' /tmp/edmp-demo-order.json)
            reason=$(jq -r '.reason // empty' /tmp/edmp-demo-order.json)
            if [ "$status" = "CONFIRMED" ] || [ "$status" = "CANCELLED" ]; then
                printf '%s %s' "$status" "$reason"
                return 0
            fi
        fi
        sleep 1
    done
    printf 'TIMEOUT '
    return 1
}

# ---------------------------------------------------------------------------------------
# 2. Happy path
# ---------------------------------------------------------------------------------------
step "Scenario 1/2 — happy path (expect CONFIRMED)"
if place_order "pi_demo_0001"; then
    pass "202 Accepted, order ${ORDER_ID}"
    dim  "correlationId: ${CORRELATION_ID:-<none>}"
    HAPPY_ORDER_ID="$ORDER_ID"; HAPPY_CORRELATION_ID="$CORRELATION_ID"
    info "  polling ${GATEWAY_URL}/api/v1/orders/${ORDER_ID} (reserve -> authorize -> capture -> confirm)..."
    RESULT=$(poll_until_terminal "$ORDER_ID"); RESULT_STATUS="${RESULT%% *}"
    if [ "$RESULT_STATUS" = "CONFIRMED" ]; then
        pass "order reached CONFIRMED"
        jq -c '{orderId, status, reason, totalAmount, lines}' /tmp/edmp-demo-order.json | sed 's/^/        /'
    else
        fail "expected CONFIRMED, got ${RESULT_STATUS} (after ${POLL_TIMEOUT_SECONDS}s)"
        [ -f /tmp/edmp-demo-order.json ] && jq -c . /tmp/edmp-demo-order.json | sed 's/^/        /'
    fi
else
    fail "order creation did not return 202"
    jq -c . /tmp/edmp-demo-body.json 2>/dev/null | sed 's/^/        /'
fi

# ---------------------------------------------------------------------------------------
# 3. Compensation path. pi_decline is the stub provider's decline token
#    (payment.provider.stub.decline-tokens) and is passed through unchanged from the order
#    request to the AuthorizePayment command, so the saga compensates: stock is reserved,
#    the authorization is declined, the reservation is released, the order is cancelled.
# ---------------------------------------------------------------------------------------
step "Scenario 2/2 — payment decline (expect CANCELLED / PAYMENT_DECLINED)"
if place_order "pi_decline"; then
    pass "202 Accepted, order ${ORDER_ID}"
    dim  "correlationId: ${CORRELATION_ID:-<none>}"
    DECLINE_ORDER_ID="$ORDER_ID"; DECLINE_CORRELATION_ID="$CORRELATION_ID"
    info "  polling ${GATEWAY_URL}/api/v1/orders/${ORDER_ID} (reserve -> decline -> release -> cancel)..."
    RESULT=$(poll_until_terminal "$ORDER_ID")
    RESULT_STATUS="${RESULT%% *}"; RESULT_REASON="${RESULT#* }"
    if [ "$RESULT_STATUS" = "CANCELLED" ] && [ "$RESULT_REASON" = "PAYMENT_DECLINED" ]; then
        pass "order reached CANCELLED with reason PAYMENT_DECLINED (stock released)"
        jq -c '{orderId, status, reason}' /tmp/edmp-demo-order.json | sed 's/^/        /'
    else
        fail "expected CANCELLED/PAYMENT_DECLINED, got ${RESULT_STATUS}/${RESULT_REASON:-<none>} (after ${POLL_TIMEOUT_SECONDS}s)"
        [ -f /tmp/edmp-demo-order.json ] && jq -c . /tmp/edmp-demo-order.json | sed 's/^/        /'
    fi
else
    fail "order creation did not return 202"
    jq -c . /tmp/edmp-demo-body.json 2>/dev/null | sed 's/^/        /'
fi

# ---------------------------------------------------------------------------------------
# 4. How to follow the story in the logs. One correlation id spans REST -> Kafka -> DB
#    across all four services (ADR-0013).
# ---------------------------------------------------------------------------------------
step "Follow either saga across all services by its correlationId"
info "  happy path   : ${HAPPY_CORRELATION_ID:-<none>}   (order ${HAPPY_ORDER_ID:-<none>})"
info "  decline path : ${DECLINE_CORRELATION_ID:-<none>}   (order ${DECLINE_ORDER_ID:-<none>})"
info ""
dim  "  docker compose -f ${COMPOSE_FILE} logs --no-log-prefix=false | grep ${HAPPY_CORRELATION_ID:-<correlationId>}"
dim  "  docker compose -f ${COMPOSE_FILE} logs order-service payment-service inventory-service | grep ${DECLINE_ORDER_ID:-<orderId>}"

step "Result"
if [ "$FAILURES" -eq 0 ]; then
    printf '%s  DEMO PASSED%s — CONFIRMED and CANCELLED(PAYMENT_DECLINED) both observed through the gateway.\n' "$GREEN$BOLD" "$RESET"
    exit 0
fi
printf '%s  DEMO FAILED%s — %s check(s) failed. Inspect the stack with: make logs\n' "$RED$BOLD" "$RESET" "$FAILURES"
exit 1
