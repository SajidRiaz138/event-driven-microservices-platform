package com.sajidriaz.orderplatform.orderservice.saga;

import java.time.Duration;

/**
 * Per-step saga budgets (ADR-0003: "Timeouts are timer-driven against the persisted
 * deadline; a step with no reply is retried or compensated").
 *
 * <p>NOTE ON THE NUMBERS: no ADR, REQUIREMENTS entry or NFR document specifies concrete
 * per-step deadlines — NFR-and-SLO only pins an end-to-end target (p95 &lt; 3s for the
 * saga to reach a terminal state). The defaults bound in
 * {@code com.sajidriaz.orderplatform.orderservice.config.SagaConfig} are therefore a
 * deliberate implementation choice: generous relative to the p95 target so a merely
 * slow participant is never mistaken for a dead one, and configurable so operations can
 * tune them without a rebuild.
 *
 * @param reservationTtlSeconds        TTL carried on the {@code ReserveStock} command, so
 *                                     inventory releases an abandoned reservation itself
 *                                     even if this orchestrator dies (ADR-0015).
 * @param awaitStockReservation        budget for a reply to {@code ReserveStock}.
 * @param awaitPaymentAuthorization    budget for a reply to {@code AuthorizePayment}.
 * @param awaitPaymentCapture          budget for a reply to {@code CapturePayment}. A
 *                                     breach of THIS budget must never cancel the order
 *                                     (ADR-0003: an unknown capture outcome is not a
 *                                     failure) — see
 *                                     {@link SagaOrchestrator#onStepTimeout(java.util.UUID)}.
 */
public record SagaTimeouts(int reservationTtlSeconds,
        Duration awaitStockReservation,
        Duration awaitPaymentAuthorization,
        Duration awaitPaymentCapture) {
}
