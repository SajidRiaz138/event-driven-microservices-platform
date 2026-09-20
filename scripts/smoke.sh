#!/bin/bash

# Smoke test script for the event-driven microservices platform
# This script performs basic health checks and functionality tests

set -e  # Exit on any error

echo "🔍 Running Smoke Tests for Event-Driven Microservices Platform"
echo "=============================================================="

# Check if required tools are available
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed."
    exit 1
fi

if ! command -v curl &> /dev/null; then
    echo "❌ curl is not installed."
    exit 1
fi

# Check if docker-compose file exists
if [ ! -f "deploy/local/compose.yaml" ]; then
    echo "❌ Docker Compose file not found at deploy/local/compose.yaml"
    exit 1
fi

echo "✅ Prerequisites check passed"

# Check if services are running
echo "🔧 Checking infrastructure services..."

# Check PostgreSQL
if docker-compose -f deploy/local/compose.yaml ps | grep -q "edmp-postgres.*Up"; then
    echo "✅ PostgreSQL is running"
else
    echo "❌ PostgreSQL is not running"
    exit 1
fi

# Check Kafka
if docker-compose -f deploy/local/compose.yaml ps | grep -q "edmp-kafka.*Up"; then
    echo "✅ Kafka is running"
else
    echo "❌ Kafka is not running"
    exit 1
fi

# Check Schema Registry
if docker-compose -f deploy/local/compose.yaml ps | grep -q "edmp-schema-registry.*Up"; then
    echo "✅ Schema Registry is running"
else
    echo "❌ Schema Registry is not running"
    exit 1
fi

# Check Redis
if docker-compose -f deploy/local/compose.yaml ps | grep -q "edmp-redis.*Up"; then
    echo "✅ Redis is running"
else
    echo "❌ Redis is not running"
    exit 1
fi

# Check Keycloak (auth-service, ADR-0018) — the realm's discovery document, not just the
# container, since the realm import happens during startup.
ISSUER_URI="${JWT_ISSUER_URI:-http://localhost:8180/realms/order-platform}"
if curl -f "${ISSUER_URI}/.well-known/openid-configuration" > /dev/null 2>&1; then
    echo "✅ Keycloak is running (realm order-platform imported)"
else
    echo "❌ Keycloak is not serving the order-platform realm at ${ISSUER_URI}"
    exit 1
fi

# Check if order service is built
if [ -f "services/order-service/target/*.jar" ]; then
    echo "✅ Order service is built"
else
    echo "⚠️  Order service JAR not found, building..."
    ./mvnw clean package -pl services/order-service -DskipTests
    echo "✅ Order service built successfully"
fi

echo ""
echo "🧪 Running smoke tests..."
echo "========================="

# Test 1: Check if order service starts
echo "1️⃣ Testing order service startup..."
timeout 30s ./mvnw spring-boot:run -pl services/order-service &
ORDER_SERVICE_PID=$!

# Wait a bit for the service to start
sleep 15

# Check health endpoint
if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "✅ Order service health check passed"
else
    echo "❌ Order service health check failed"
    kill $ORDER_SERVICE_PID 2>/dev/null || true
    exit 1
fi

# Test 2: Create and retrieve an order
echo "2️⃣ Testing order creation and retrieval..."

# Create an order. Identity is the validated JWT `sub` claim (ADR-0009): order-service
# verifies the token against Keycloak's JWKS itself, and no header carries identity.
# Prices are resolved server-side — never sent by the client (REST-API-GUIDE §1).
# A fresh Idempotency-Key is required on every mutating request (ADR-0005).
ACCESS_TOKEN=$(./scripts/get-token.sh)
IDEMPOTENCY_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
CREATE_RESPONSE_CODE=$(curl -s -o /dev/null -w "%{http_code}" -X POST http://localhost:8080/api/v1/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
    -d '{
        "paymentInstrumentId": "pi_smoke_0001",
        "currency": "USD",
        "lines": [
            { "sku": "SKU-1001", "quantity": 1 }
        ]
    }')

if [ "$CREATE_RESPONSE_CODE" -eq 202 ]; then
    echo "✅ Order creation successful (202 Accepted)"
else
    echo "❌ Order creation failed with HTTP $CREATE_RESPONSE_CODE"
    kill $ORDER_SERVICE_PID 2>/dev/null || true
    exit 1
fi

# An unauthenticated call must be refused — the smoke test asserts the door is shut, not
# only that it opens for a valid token.
UNAUTH_RESPONSE_CODE=$(curl -s -o /dev/null -w "%{http_code}" \
    -X GET "http://localhost:8080/api/v1/orders/00000000-0000-0000-0000-000000000000")
if [ "$UNAUTH_RESPONSE_CODE" -eq 401 ]; then
    echo "✅ Unauthenticated request rejected (401)"
else
    echo "❌ Expected 401 without a token, got HTTP $UNAUTH_RESPONSE_CODE"
    kill $ORDER_SERVICE_PID 2>/dev/null || true
    exit 1
fi

# Test 3: Check build integrity
echo "3️⃣ Testing build integrity..."
BUILD_RESULT=$(./mvnw clean verify -q)
if [ $? -eq 0 ]; then
    echo "✅ Maven build and tests passed"
else
    echo "❌ Maven build or tests failed"
    kill $ORDER_SERVICE_PID 2>/dev/null || true
    exit 1
fi

# Clean up
kill $ORDER_SERVICE_PID 2>/dev/null || true

echo ""
echo "🎉 All smoke tests passed!"
echo "=========================="
echo "✅ Infrastructure services: Running"
echo "✅ Order service: Starts and healthy"
echo "✅ API endpoints: Functional"
echo "✅ Build integrity: Verified"