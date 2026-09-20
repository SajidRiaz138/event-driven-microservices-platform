# Event-Driven Microservices Platform — developer entrypoint.
# Run `make help` for the list of targets.

.DEFAULT_GOAL := help
SHELL := /bin/bash

# ---- Config ----
COMPOSE ?= docker compose
COMPOSE_FILE ?= deploy/local/compose.yaml

.PHONY: help
help: ## Show this help
	@grep -E '^[a-zA-Z0-9_-]+:.*?## .*$$' $(MAKEFILE_LIST) | \
		awk 'BEGIN {FS = ":.*?## "}; {printf "  \033[36m%-16s\033[0m %s\n", $$1, $$2}'

.PHONY: build
build: ## Build all modules (Maven, skip tests)
	./mvnw -q -DskipTests clean install

.PHONY: test
test: ## Run all tests (unit + integration)
	./mvnw -q verify

.PHONY: unit-test
unit-test: ## Run unit tests only (fast, no containers) — Surefire *Test
	./mvnw -q test

.PHONY: integration-test
integration-test: ## Run integration tests only (Testcontainers) — Failsafe *IT
	./mvnw -q failsafe:integration-test failsafe:verify

.PHONY: up
up: ## Start the local stack (Postgres, Kafka, Schema Registry, Redis, observability) + services
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build

.PHONY: down
down: ## Stop the local stack and remove volumes
	$(COMPOSE) -f $(COMPOSE_FILE) down -v

.PHONY: logs
logs: ## Tail logs from the local stack
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f

.PHONY: demo
demo: ## Run the scripted end-to-end demo (happy path + payment-decline compensation)
	./scripts/demo.sh

.PHONY: smoke
smoke: ## Quick smoke test against a running stack
	./scripts/smoke.sh

.PHONY: clean
clean: ## Maven clean
	./mvnw -q clean
