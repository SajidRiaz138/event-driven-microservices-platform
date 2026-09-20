# ADR-0001: Record architecture decisions

- **Status:** Accepted
- **Date:** 2026-09-19
- **Deciders:** Platform architect

## Context

This platform makes a number of non-obvious architectural choices (async saga,
outbox, polyglot persistence, per-service auth). Without a written record, the
*reasoning* behind each choice is lost, and reviewers cannot tell a deliberate
decision from an accident. For a system intended to demonstrate architectural
judgment, the decision trail is as important as the code.

## Decision

We will record every architecturally significant decision as an ADR in
`docs/adr/`, using the lightweight MADR format. An ADR is written when a decision:

- affects the structure, dependencies, or interfaces of the system, or
- involves a trade-off a future reader would reasonably question.

ADRs are immutable once `Accepted`. A changed decision is captured in a new ADR
that supersedes the old one.

## Consequences

- **Positive:** The rationale is preserved; onboarding and review are faster; the
  platform demonstrates deliberate engineering.
- **Negative:** A small, ongoing writing cost — accepted as worthwhile.
