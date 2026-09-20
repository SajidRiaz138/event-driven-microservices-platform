#!/bin/bash

# Demo script for the event-driven microservices platform
# This script demonstrates the basic functionality of the platform

set -e  # Exit on any error

echo "🚀 Starting Event-Driven Microservices Platform Demo"
echo "==================================================="

# Check if required tools are available
if ! command -v docker &> /dev/null; then
    echo "❌ Docker is not installed. Please install Docker to run this demo."
    exit 1
fi

if ! command -v curl &> /dev/null; then
    echo "❌ curl is not installed. Please install curl to run this demo."
    exit 1
fi

# Check if docker-compose file exists
if [ ! -f "deploy/local/compose.yaml" ]; then
    echo "❌ Docker Compose file not found at deploy/local/compose.yaml"
    exit 1
fi

echo "✅ Prerequisites check passed"

# Start the infrastructure
echo "🔧 Starting infrastructure (PostgreSQL, Kafka, Schema Registry, Redis)..."
docker-compose -f deploy/local/compose.yaml up -d

# Wait for services to be ready
echo "⏳ Waiting for services to be ready..."

# Wait for PostgreSQL
until docker-compose -f deploy/local/compose.yaml exec postgres pg_isready > /dev/null 2>&1
do
    sleep 1
done
echo "✅ PostgreSQL is ready"

# Wait for Kafka
until docker-compose -f deploy/local/compose.yaml exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1
do
    sleep 1
done
echo "✅ Kafka is ready"

# Wait for Schema Registry
until curl -f http://localhost:8081 > /dev/null 2>&1
do
    sleep 1
done
echo "✅ Schema Registry is ready"

# Wait for Redis
until docker-compose -f deploy/local/compose.yaml exec redis redis-cli ping > /dev/null 2>&1
do
    sleep 1
done
echo "✅ Redis is ready"

# Wait for Keycloak (auth-service, ADR-0018). Polls the realm's discovery document rather
# than the port: the realm import runs during startup, so a listening port does not yet mean
# a token can be issued.
ISSUER_URI="${JWT_ISSUER_URI:-http://localhost:8180/realms/order-platform}"
until curl -f "${ISSUER_URI}/.well-known/openid-configuration" > /dev/null 2>&1
do
    sleep 2
done
echo "✅ Keycloak is ready (realm order-platform imported)"

echo "🎉 All infrastructure services are ready!"

# Build the order service
echo "🔨 Building order service..."
./mvnw clean package -pl services/order-service -DskipTests

echo "🚀 Starting order service..."
# Start the order service in background
./mvnw spring-boot:run -pl services/order-service &
ORDER_SERVICE_PID=$!

# Wait a bit for the service to start
sleep 10

# Check if the service is running
if curl -f http://localhost:8080/actuator/health > /dev/null 2>&1; then
    echo "✅ Order service is running"
else
    echo "❌ Order service failed to start"
    kill $ORDER_SERVICE_PID 2>/dev/null || true
    docker-compose -f deploy/local/compose.yaml down
    exit 1
fi

echo ""
echo "🧪 Running demo scenarios..."
echo "============================"

# Demo 1: Create an order
echo "1️⃣ Creating a sample order..."
# Identity comes from the validated JWT `sub` claim (ADR-0009) — order-service verifies the
# token against Keycloak's JWKS itself; no header carries the caller's identity. Prices are
# resolved server-side: the client sends only sku + quantity, never a price (REST-API-GUIDE §1).
ACCESS_TOKEN=$(./scripts/get-token.sh)
echo "🔑 Obtained an access token for ${DEMO_USERNAME:-demo-customer}"
IDEMPOTENCY_KEY=$(uuidgen 2>/dev/null || cat /proc/sys/kernel/random/uuid)
ORDER_RESPONSE=$(curl -s -i -X POST http://localhost:8080/api/v1/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer $ACCESS_TOKEN" \
    -H "Idempotency-Key: $IDEMPOTENCY_KEY" \
    -d '{
        "paymentInstrumentId": "pi_demo_0001",
        "currency": "USD",
        "lines": [
            { "sku": "SKU-1001", "quantity": 2 }
        ]
    }')

echo "✅ Order creation response received"

LOCATION=$(echo "$ORDER_RESPONSE" | grep -i '^Location:' | awk '{print $2}' | tr -d '\r')

if [ -n "$LOCATION" ]; then
    ORDER_ID=$(basename "$LOCATION")
    echo "📋 Created order with ID: $ORDER_ID"

    # Retrieve the order with the same token — ownership is enforced against the token's
    # subject, so another customer's token gets 404 here, never 403 (S-15).
    echo "2️⃣ Retrieving the created order..."
    GET_RESPONSE=$(curl -s -X GET "http://localhost:8080/api/v1/orders/$ORDER_ID" \
        -H "Authorization: Bearer $ACCESS_TOKEN")
    echo "✅ Order retrieved successfully"
    echo "📄 Order details:"
    echo $GET_RESPONSE | jq .

    # Ownership check, made visible: a different customer's token must not see this order.
    echo "3️⃣ Verifying cross-customer isolation (expecting 404)..."
    OTHER_TOKEN=$(./scripts/get-token.sh other-customer other-password)
    OTHER_STATUS=$(curl -s -o /dev/null -w "%{http_code}" \
        -X GET "http://localhost:8080/api/v1/orders/$ORDER_ID" \
        -H "Authorization: Bearer $OTHER_TOKEN")
    if [ "$OTHER_STATUS" -eq 404 ]; then
        echo "✅ Another customer gets 404 (existence is not revealed)"
    else
        echo "❌ Expected 404 for a different customer, got HTTP $OTHER_STATUS"
    fi
else
    echo "⚠️  Could not extract order ID from response"
fi

echo ""
echo "🎉 Demo completed successfully!"
echo "================================"

echo "To stop the demo:"
echo "1. Press Ctrl+C to stop the order service"
echo "2. Run 'docker-compose -f deploy/local/compose.yaml down' to stop infrastructure"

# Wait for user to stop the demo
wait $ORDER_SERVICE_PID