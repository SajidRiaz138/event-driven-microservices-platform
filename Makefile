# Event-Driven Microservices Platform — developer entrypoint.
# Run `make help` for the list of targets.

.DEFAULT_GOAL := help
SHELL := /bin/bash

# ---- Config ----
COMPOSE ?= docker compose
COMPOSE_FILE ?= deploy/local/compose.yaml

# ---- Kubernetes / Helm (ADR-0008) ----
# See docs/deployment/kubernetes-minikube.md for the full runbook these targets shortcut.
HELM_CHARTS ?= deploy/helm/charts
UMBRELLA ?= deploy/helm/platform-umbrella
HELM_RELEASE ?= platform
K8S_NAMESPACE ?= edmp
MINIKUBE_PROFILE ?= edmp
SERVICES ?= api-gateway order-service payment-service inventory-service
# The realm export and the Postgres init SQL are read from the one place they already live,
# so the charts cannot drift from the compose stack.
HELM_FILES = \
	--set-file postgres.initSql=deploy/local/postgres-init/01-extensions.sql \
	--set-file keycloak.realmJson=services/auth-service/realm/order-platform-realm.json

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
up: ## Start the whole local stack (Postgres, Kafka, Redis, Keycloak + all 4 services) and wait until healthy
	$(COMPOSE) -f $(COMPOSE_FILE) up -d --build --wait
	@echo
	@echo "Stack is up. Gateway: http://localhost:8090  Keycloak: http://localhost:8180"
	@echo "Next: make demo   (happy path + payment-decline compensation)"

.PHONY: down
down: ## Stop the local stack and remove volumes
	$(COMPOSE) -f $(COMPOSE_FILE) down -v

.PHONY: logs
logs: ## Tail logs from the local stack
	$(COMPOSE) -f $(COMPOSE_FILE) logs -f

.PHONY: demo
demo: ## Run the scripted end-to-end demo (happy path + payment-decline compensation)
	./scripts/demo.sh

.PHONY: token
token: ## Print an access token for the demo user (needs the stack up: make up)
	@./scripts/get-token.sh; echo

.PHONY: smoke
smoke: ## Quick smoke test against a running stack
	./scripts/smoke.sh

# ---- Kubernetes / Helm ----------------------------------------------------------------

.PHONY: k8s-deps
k8s-deps: ## Resolve Helm chart dependencies (vendors platform-lib into each service chart)
	@for c in $(SERVICES); do helm dependency update $(HELM_CHARTS)/$$c > /dev/null; done
	@helm dependency update $(UMBRELLA) > /dev/null
	@echo "Chart dependencies resolved."

.PHONY: k8s-lint
k8s-lint: k8s-deps ## Lint and render the umbrella chart (no cluster needed)
	helm lint $(UMBRELLA) -f $(UMBRELLA)/values-minikube.yaml $(HELM_FILES)
	@helm template $(HELM_RELEASE) $(UMBRELLA) -n $(K8S_NAMESPACE) \
		-f $(UMBRELLA)/values-minikube.yaml $(HELM_FILES) > /dev/null
	@echo "Umbrella chart renders."

.PHONY: k8s-images
k8s-images: ## Load the four locally built service images into the minikube cluster
	@for c in $(SERVICES); do \
		echo "loading edmp/$$c:local"; \
		minikube -p $(MINIKUBE_PROFILE) image load edmp/$$c:local; \
	done

.PHONY: k8s-install
k8s-install: k8s-deps ## Install/upgrade the platform in minikube and wait until every pod is Ready
	helm upgrade --install $(HELM_RELEASE) $(UMBRELLA) \
		-n $(K8S_NAMESPACE) --create-namespace \
		-f $(UMBRELLA)/values-minikube.yaml $(HELM_FILES) \
		--wait --timeout 10m
	kubectl -n $(K8S_NAMESPACE) get pods

.PHONY: k8s-uninstall
k8s-uninstall: ## Remove the platform release (keeps the namespace and the Postgres PVC)
	-helm uninstall $(HELM_RELEASE) -n $(K8S_NAMESPACE)

.PHONY: clean
clean: ## Maven clean
	./mvnw -q clean
