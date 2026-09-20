package com.sajidriaz.orderplatform.paymentservice.web;

import com.sajidriaz.orderplatform.paymentservice.entity.PaymentOperationEntity;
import com.sajidriaz.orderplatform.paymentservice.service.PaymentService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * An operator view of an order's payment operations, so an UNKNOWN outcome is inspectable rather than
 * something only visible in logs. This is the human end of the reconciliation story in ADR-0016.
 *
 * <p>SECURITY: this endpoint is unauthenticated, matching the platform's Phase-1 position — the
 * gateway and auth-service (ADR-0009/ADR-0018) are not built yet, so there is no token to validate.
 * It is read-only and exposes no card data (none is stored), but it does expose payment status and
 * amounts for any order id, so it must NOT be reachable from outside the cluster as it stands. When
 * the gateway lands this belongs behind authentication and an operator authorization check.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentOperationsController {

    private final PaymentService paymentService;

    public PaymentOperationsController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** The operations recorded for an order, oldest first. 404 when the order is unknown here. */
    @GetMapping("/orders/{orderId}/operations")
    public ResponseEntity<List<OperationView>> operations(@PathVariable UUID orderId) {
        List<PaymentOperationEntity> operations = paymentService.operationsForOrder(orderId);
        if (operations.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(operations.stream().map(OperationView::of).toList());
    }

    /**
     * @param status PENDING, SUCCEEDED, FAILED or UNKNOWN — UNKNOWN meaning the provider's outcome is
     *               genuinely not established, not that it failed
     */
    public record OperationView(UUID operationId, String type, String status, String providerReference,
                                long amountMinorUnits, String currency, String failureReason,
                                int reconcileAttempts, Instant createdAt, Instant updatedAt) {

        static OperationView of(PaymentOperationEntity operation) {
            return new OperationView(
                    operation.getId(),
                    operation.getOperationType().name(),
                    operation.getStatus().name(),
                    operation.getProviderReference(),
                    operation.getAmount().minorUnits(),
                    operation.getAmount().currency(),
                    operation.getFailureReason(),
                    operation.getReconcileAttempts(),
                    operation.getCreatedAt(),
                    operation.getUpdatedAt());
        }
    }
}
