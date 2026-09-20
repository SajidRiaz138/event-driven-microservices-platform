package com.sajidriaz.orderplatform.paymentservice.entity;

import com.sajidriaz.orderplatform.common.money.Money;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The operation state machine of ADR-0016 §2, in isolation. The rules under test are the ones that
 * stop money being lost or taken twice, so each is asserted directly rather than inferred from
 * higher-level behaviour.
 */
class PaymentOperationEntityTest
{

    private static final Money AMOUNT = Money.of(1999, "USD");

    @Test
    void anOperationStartsPendingBeforeTheProviderIsCalled()
    {
        PaymentOperationEntity operation = operation(OperationType.CAPTURE);

        // PENDING exists so a crash mid-call leaves evidence that the call was made, rather than no
        // record at all of a request that may have moved money.
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.PENDING);
        assertThat(operation.getStatus().isUnresolved()).isTrue();
        assertThat(operation.getProviderIdempotencyKey()).isNotBlank();
    }

    @Test
    void aTimeoutRecordsUnknown_whichIsNeitherSuccessNorFailure()
    {
        PaymentOperationEntity operation = operation(OperationType.CAPTURE);

        operation.markUnknown("cap_ref_1");

        assertThat(operation.getStatus()).isEqualTo(OperationStatus.UNKNOWN);
        assertThat(operation.getStatus().isResolved()).isFalse();
        assertThat(operation.getStatus().isUnresolved()).isTrue();
        // Any reference obtained before the response was lost is kept: it is the handle
        // reconciliation uses.
        assertThat(operation.getProviderReference()).isEqualTo("cap_ref_1");
    }

    @Test
    void unknownCanBeResolvedEitherWay_butOnlyPositively()
    {
        PaymentOperationEntity captured = operation(OperationType.CAPTURE);
        captured.markUnknown(null);
        captured.resolve(OperationStatus.SUCCEEDED, "cap_ref_2", null);
        assertThat(captured.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);

        PaymentOperationEntity notCaptured = operation(OperationType.CAPTURE);
        notCaptured.markUnknown(null);
        notCaptured.resolve(OperationStatus.FAILED, null, "no_such_operation");
        assertThat(notCaptured.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(notCaptured.getFailureReason()).isEqualTo("no_such_operation");

        // The same ambiguous signal resolves in opposite directions, which is exactly why UNKNOWN
        // must never be assumed to mean one of them.
    }

    @Test
    void resolvingToAnUnresolvedStatusIsRejected()
    {
        PaymentOperationEntity operation = operation(OperationType.CAPTURE);

        assertThatThrownBy(() -> operation.resolve(OperationStatus.PENDING, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> operation.resolve(OperationStatus.UNKNOWN, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anEstablishedOutcomeCannotBeFlipped()
    {
        PaymentOperationEntity operation = operation(OperationType.CAPTURE);
        operation.resolve(OperationStatus.SUCCEEDED, "cap_ref_3", null);

        // A captured payment silently becoming FAILED on a later message would mean the system
        // forgot it had taken money.
        assertThatThrownBy(() -> operation.resolve(OperationStatus.FAILED, null, "late_failure"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already SUCCEEDED");
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    void reResolvingToTheSameOutcomeIsAllowed_soRedeliveryIsHarmless()
    {
        PaymentOperationEntity operation = operation(OperationType.AUTHORIZE);
        operation.resolve(OperationStatus.SUCCEEDED, "auth_ref", null);

        operation.resolve(OperationStatus.SUCCEEDED, "auth_ref", null);

        assertThat(operation.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    void anEstablishedOutcomeCannotRegressToUnknown()
    {
        PaymentOperationEntity operation = operation(OperationType.CAPTURE);
        operation.resolve(OperationStatus.SUCCEEDED, "cap_ref_4", null);

        // Once the truth is known, a later ambiguous attempt must not erase it.
        assertThatThrownBy(() -> operation.markUnknown(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not regress");
        assertThat(operation.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
    }

    @Test
    void reconcileAttemptsAreCounted()
    {
        PaymentOperationEntity operation = operation(OperationType.CAPTURE);
        operation.markUnknown(null);

        assertThat(operation.recordReconcileAttempt()).isEqualTo(1);
        assertThat(operation.recordReconcileAttempt()).isEqualTo(2);
        assertThat(operation.getReconcileAttempts()).isEqualTo(2);
    }

    @Test
    void amountIsCarriedAsIntegerMinorUnits()
    {
        PaymentOperationEntity operation = operation(OperationType.CAPTURE);

        // Money is minor units + currency, never floating point (NFR §7, ADR-0016 §4).
        assertThat(operation.getAmount()).isEqualTo(Money.of(1999, "USD"));
        assertThat(operation.getAmount().minorUnits()).isEqualTo(1999L);
    }

    private PaymentOperationEntity operation(OperationType type)
    {
        UUID orderId = UUID.randomUUID();
        PaymentIntentEntity intent = new PaymentIntentEntity(UUID.randomUUID(), orderId,
                UUID.randomUUID(), AMOUNT, "pi_token");
        PaymentAttemptEntity attempt =
                new PaymentAttemptEntity(UUID.randomUUID(), intent, "pi_token");
        return new PaymentOperationEntity(UUID.randomUUID(), attempt, type,
                type.name().toLowerCase() + "-" + attempt.getId(), AMOUNT);
    }
}
