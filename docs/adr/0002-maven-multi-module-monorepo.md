# ADR-0002: Maven multi-module monorepo

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The platform is several services plus shared contracts (event schemas, gRPC
definitions, common Java types). We must decide how to organise the build and the
repositories: multiple repos vs a monorepo, and Gradle vs Maven.

## Decision

Use a **single Git repository (monorepo)** with a **Maven multi-module reactor**.

- A parent `pom.xml` owns the Spring Boot 4.1.x BOM, Java 21 compiler settings, and
  centralised dependency/plugin management.
- Each service and each shared library is a Maven module.
- Shared contracts (`shared/common-lib`, `shared/avro-schemas`, `shared/proto`,
  `shared/openapi`) are modules that services depend on, guaranteeing one source of
  truth for events and DTOs.

We choose **Maven over Gradle** here deliberately: Maven's declarative model and
ubiquity in enterprise Java make the build immediately readable to any reviewer,
which matters more for this project than Gradle's incremental-build speed.

## Consequences

- **Positive:** One source of truth for contracts; atomic cross-service changes;
  consistent versions via the parent BOM; easy for a reviewer to build all at once.
- **Negative:** A naive CI rebuilds everything on any change. Mitigated later with
  build-affected detection and caching (tracked in the CI work).
- **Trade-off accepted:** Monorepo coupling is acceptable for a cohesive platform
  owned by one team; independent repos would add ceremony without benefit here.
