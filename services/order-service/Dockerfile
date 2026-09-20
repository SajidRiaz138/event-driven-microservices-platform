# Multi-stage build for order-service.
#
# NOTE ON BUILD CONTEXT: this Dockerfile must be built with the REPO ROOT as the
# build context (not this directory), because Docker's COPY cannot reach paths
# outside the build context — and order-service depends on the sibling
# shared/avro-schemas and shared/common-lib modules (Maven multi-module monorepo).
# Build from the repo root:
#   docker build -f services/order-service/Dockerfile -t order-service .

# 1. Build stage
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app

# Copy Maven wrapper + root POM first (better Docker layer caching on rebuilds).
COPY pom.xml mvnw mvnw.cmd ./
COPY .mvn .mvn

# Copy only the modules order-service needs to build (itself + its two shared deps).
COPY shared/avro-schemas shared/avro-schemas
COPY shared/common-lib shared/common-lib
COPY services/order-service services/order-service

RUN chmod +x mvnw && \
    ./mvnw -B -q clean package -DskipTests -pl services/order-service,shared/avro-schemas,shared/common-lib -am

# 2. Runtime stage
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# Non-root user (least privilege).
RUN addgroup -g 1001 -S appuser && \
    adduser -u 1001 -S appuser -G appuser

COPY --from=builder /app/services/order-service/target/*.jar app.jar

RUN chown -R appuser:appuser /app

USER appuser

EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD wget --no-verbose --tries=1 --spider http://localhost:8080/actuator/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
