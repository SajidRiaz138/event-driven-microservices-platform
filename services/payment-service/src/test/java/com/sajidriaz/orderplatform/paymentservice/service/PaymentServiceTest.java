package com.sajidriaz.orderplatform.paymentservice.service;

import com.sajidriaz.orderplatform.common.money.Money;
import com.sajidriaz.orderplatform.paymentservice.entity.OperationStatus;
import com.sajidriaz.orderplatform.paymentservice.entity.OperationType;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentAttemptEntity;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentIntentEntity;
import com.sajidriaz.orderplatform.paymentservice.entity.PaymentOperationEntity;
import com.sajidriaz.orderplatform.paymentservice.provider.PaymentProvider;
import com.sajidriaz.orderplatform.paymentservice.provider.StubPaymentProvider;
import com.sajidriaz.orderplatform.paymentservice.repository.PaymentAttemptRepository;
import com.sajidriaz.orderplatform.paymentservice.repository.PaymentIntentRepository;
import com.sajidriaz.orderplatform.paymentservice.repository.PaymentOperationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The payment state machine of ADR-0016 end to end at the unit level, against the stub provider and
 * in-memory repositories.
 *
 * <p>The provider is a real {@link StubPaymentProvider} wrapped in a Mockito spy rather than a mock:
 * the properties under test — capture-once, idempotency-key reuse, the same ambiguous signal
 * resolving both ways — depend on the provider genuinely being idempotent on its key, which a mock
 * returning canned values would not be, so the tests would prove nothing.
 */
class PaymentServiceTest
{

    private static final String GOOD_TOKEN = "pi_ok";
    private static final String DECLINE_TOKEN = "pi_decline";
    private static final String TIMEOUT_CAPTURED_TOKEN = "pi_capture_timeout_captured";
    private static final String TIMEOUT_LOST_TOKEN = "pi_capture_timeout_lost";
    private static final String TIMEOUT_UNANSWERABLE_TOKEN = "pi_capture_timeout_unanswerable";
    private static final Money AMOUNT = Money.of(1999, "USD");

    private InMemoryRepositories repositories;
    private PaymentProvider provider;
    private PaymentEventPublisher events;
    private PaymentService service;
    private ReconciliationService reconciliation;

    @BeforeEach
    void setUp()
    {
        repositories = new InMemoryRepositories();
        provider = spy(new StubPaymentProvider(
                List.of(DECLINE_TOKEN),
                List.of(TIMEOUT_CAPTURED_TOKEN),
                List.of(TIMEOUT_LOST_TOKEN),
                List.of(TIMEOUT_UNANSWERABLE_TOKEN)));
        events = mock(PaymentEventPublisher.class);
        service = new PaymentService(repositories.intents, repositories.attempts, repositories.operations,
                provider, events);
        reconciliation = new ReconciliationService(repositories.operations, provider, events, 50, 3);
    }

    // ---------------------------------------------------------------- authorize

    @Test
    void authorize_approved_recordsSucceededOperationAndEmitsPaymentAuthorized()
    {
        UUID orderId = UUID.randomUUID();

        service.authorize(authorizeCommand(orderId, GOOD_TOKEN), UUID.randomUUID(), UUID.randomUUID());

        PaymentOperationEntity authorization = onlyOperation(OperationType.AUTHORIZE);
        assertThat(authorization.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(authorization.getProviderReference()).isNotBlank();
        verify(events).authorized(any(), any(), any());
        verify(events, never()).declined(any(), any(), any());
    }

    @Test
    void authorize_declined_recordsFailedOperationAndEmitsPaymentDeclined()
    {
        UUID orderId = UUID.randomUUID();

        service.authorize(authorizeCommand(orderId, DECLINE_TOKEN), UUID.randomUUID(), UUID.randomUUID());

        PaymentOperationEntity authorization = onlyOperation(OperationType.AUTHORIZE);
        assertThat(authorization.getStatus()).isEqualTo(OperationStatus.FAILED);
        assertThat(authorization.getFailureReason()).isEqualTo("card_declined");
        verify(events).declined(any(), any(), any());
        verify(events, never()).authorized(any(), any(), any());
    }

    @Test
    void authorize_repeatedForTheSameAttempt_callsTheProviderOnceAndReEmitsTheReply()
    {
        UUID orderId = UUID.randomUUID();
        PaymentService.AuthorizeCommand command = authorizeCommand(orderId, GOOD_TOKEN);

        service.authorize(command, UUID.randomUUID(), UUID.randomUUID());
        service.authorize(command, UUID.randomUUID(), UUID.randomUUID());

        verify(provider, times(1)).authorize(any());
        assertThat(repositories.allOperations(OperationType.AUTHORIZE)).hasSize(1);
        // Re-emitted, because a repeated command usually means the first reply was never seen.
        verify(events, times(2)).authorized(any(), any(), any());
    }

    @Test
    void asecondAttemptForTheSameOrderIsAllowed_dedupIsNeverOnTheOrder()
    {
        // ADR-0016's central modelling point: a declined card must be retryable with another
        // instrument, which a UNIQUE(order_id) dedup would forbid outright.
        UUID orderId = UUID.randomUUID();
        service.authorize(authorizeCommand(orderId, DECLINE_TOKEN), UUID.randomUUID(), UUID.randomUUID());

        service.authorize(new PaymentService.AuthorizeCommand(orderId, UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), AMOUNT, GOOD_TOKEN),
                UUID.randomUUID(), UUID.randomUUID());

        assertThat(repositories.allOperations(OperationType.AUTHORIZE)).hasSize(2);
        assertThat(repositories.intents.findByOrderId(orderId)).isPresent();
        // One intent per order, two attempts against it.
        assertThat(repositories.attempts.findByIntentIdOrderByCreatedAtAsc(
                repositories.intents.findByOrderId(orderId).orElseThrow().getId())).hasSize(2);
        verify(events).declined(any(), any(), any());
        verify(events).authorized(any(), any(), any());
    }

    // ------------------------------------------------------------------ capture

    @Test
    void capture_afterAuthorization_succeedsAndEmitsThePivotEvent()
    {
        UUID orderId = authorizedOrder(GOOD_TOKEN);

        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        PaymentOperationEntity capture = onlyOperation(OperationType.CAPTURE);
        assertThat(capture.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(capture.getProviderReference()).startsWith("cap_");
        verify(events).captured(any(), any(), any());
    }

    @Test
    void capture_withNoEstablishedAuthorization_emitsCaptureFailedAndNeverCallsTheProvider()
    {
        UUID orderId = UUID.randomUUID();
        // A declined authorization is not an authorization.
        service.authorize(authorizeCommand(orderId, DECLINE_TOKEN), UUID.randomUUID(), UUID.randomUUID());

        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        verify(provider, never()).capture(any());
        verify(events).captureFailed(any(), any(), any());
        assertThat(repositories.allOperations(OperationType.CAPTURE))
                .allSatisfy(operation -> assertThat(operation.getStatus()).isEqualTo(OperationStatus.FAILED));
    }

    @Test
    void capture_repeated_doesNotCaptureTwice_andReEmitsThePivotEvent()
    {
        UUID orderId = authorizedOrder(GOOD_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        // Capture-once (ADR-0016 §2): the second command must not reach the provider at all.
        verify(provider, times(1)).capture(any());
        assertThat(repositories.allOperations(OperationType.CAPTURE)).hasSize(1);
        verify(events, times(2)).captured(any(), any(), any());
    }

    @Test
    void capture_reusesTheSameProviderIdempotencyKeyForAnOrdersCapture()
    {
        UUID orderId = authorizedOrder(GOOD_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        PaymentOperationEntity capture = onlyOperation(OperationType.CAPTURE);
        // Derived from the attempt, not random: a random key per retry would let the provider treat
        // a retry as a fresh charge.
        assertThat(capture.getProviderIdempotencyKey())
                .isEqualTo("cap-" + capture.getAttempt().getId());
    }

    // ------------------------------------------------- the UNKNOWN capture path

    @Test
    void capture_whenTheResponseIsLost_recordsUnknownAndAnnouncesIt()
    {
        UUID orderId = authorizedOrder(TIMEOUT_CAPTURED_TOKEN);

        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        PaymentOperationEntity capture = onlyOperation(OperationType.CAPTURE);
        // Not FAILED and not SUCCEEDED: the funds may or may not have moved (scenario S-17).
        assertThat(capture.getStatus()).isEqualTo(OperationStatus.UNKNOWN);
        verify(events).captureUnknown(any(), any(), any());
        verify(events, never()).captured(any(), any(), any());
        verify(events, never()).captureFailed(any(), any(), any());
    }

    @Test
    void capture_whileUnknown_isNotRetriedAgainstTheProvider()
    {
        UUID orderId = authorizedOrder(TIMEOUT_CAPTURED_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        // Reconciliation, not a blind retry, is what resolves an unknown outcome.
        verify(provider, times(1)).capture(any());
        assertThat(onlyOperation(OperationType.CAPTURE).getStatus()).isEqualTo(OperationStatus.UNKNOWN);
        verify(events, times(2)).captureUnknown(any(), any(), any());
    }

    @Test
    void reconciliation_findsTheFundsWereTaken_resolvesSucceededAndEmitsCaptured()
    {
        UUID orderId = authorizedOrder(TIMEOUT_CAPTURED_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());
        PaymentOperationEntity capture = onlyOperation(OperationType.CAPTURE);

        reconciliation.reconcile(capture);

        assertThat(capture.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
        assertThat(capture.getProviderReference()).startsWith("cap_");
        // The same event the direct path would have emitted, so a late resolution still advances the
        // saga. No second capture was attempted.
        verify(events).captured(any(), any(), any());
        verify(provider, times(1)).capture(any());
    }

    @Test
    void reconciliation_findsTheFundsWereNotTaken_resolvesFailedAndEmitsCaptureFailed()
    {
        UUID orderId = authorizedOrder(TIMEOUT_LOST_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());
        PaymentOperationEntity capture = onlyOperation(OperationType.CAPTURE);
        assertThat(capture.getStatus()).isEqualTo(OperationStatus.UNKNOWN);

        reconciliation.reconcile(capture);

        // The identical ambiguous signal resolving the other way — which is the whole reason UNKNOWN
        // may never be assumed to mean either outcome.
        assertThat(capture.getStatus()).isEqualTo(OperationStatus.FAILED);
        verify(events).captureFailed(any(), any(), any());
        verify(events, never()).captured(any(), any(), any());
    }

    @Test
    void reconciliation_whenTheProviderStillCannotSay_leavesTheOperationUnknownAndEmitsNothing()
    {
        UUID orderId = authorizedOrder(TIMEOUT_UNANSWERABLE_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());
        PaymentOperationEntity capture = onlyOperation(OperationType.CAPTURE);

        reconciliation.reconcile(capture);
        reconciliation.reconcile(capture);

        // Nothing about elapsed time or attempt count converts ignorance into a fact.
        assertThat(capture.getStatus()).isEqualTo(OperationStatus.UNKNOWN);
        assertThat(capture.getReconcileAttempts()).isEqualTo(2);
        verify(events, never()).captured(any(), any(), any());
        verify(events, never()).captureFailed(any(), any(), any());
    }

    @Test
    void reconciliation_ignoresOperationsThatAreAlreadyResolved()
    {
        UUID orderId = authorizedOrder(GOOD_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());
        PaymentOperationEntity capture = onlyOperation(OperationType.CAPTURE);

        reconciliation.reconcile(capture);

        assertThat(capture.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
        // One captured event from the capture itself; reconciliation must not emit a second.
        verify(events, times(1)).captured(any(), any(), any());
    }

    // ------------------------------------------------------------------- refund

    @Test
    void refund_isRefusedWhenNoCaptureIsEstablished()
    {
        UUID orderId = authorizedOrder(TIMEOUT_CAPTURED_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());
        assertThat(onlyOperation(OperationType.CAPTURE).getStatus()).isEqualTo(OperationStatus.UNKNOWN);

        service.refund(new PaymentService.RefundCommand(orderId, UUID.randomUUID(), UUID.randomUUID(),
                AMOUNT), UUID.randomUUID(), UUID.randomUUID());

        // Refunding against an UNKNOWN capture could return money that was never taken.
        verify(provider, never()).refund(any());
        verify(events, never()).refunded(any(), any(), any());
        assertThat(repositories.allOperations(OperationType.REFUND)).isEmpty();
    }

    @Test
    void refund_afterAnEstablishedCapture_succeedsAndEmitsPaymentRefunded()
    {
        UUID orderId = authorizedOrder(GOOD_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());

        service.refund(new PaymentService.RefundCommand(orderId, UUID.randomUUID(), UUID.randomUUID(),
                AMOUNT), UUID.randomUUID(), UUID.randomUUID());

        PaymentOperationEntity refund = onlyOperation(OperationType.REFUND);
        assertThat(refund.getStatus()).isEqualTo(OperationStatus.SUCCEEDED);
        verify(events).refunded(any(), any(), any());
    }

    @Test
    void refund_repeated_doesNotRefundTwice()
    {
        UUID orderId = authorizedOrder(GOOD_TOKEN);
        service.capture(orderId, UUID.randomUUID(), UUID.randomUUID());
        PaymentService.RefundCommand command = new PaymentService.RefundCommand(orderId,
                UUID.randomUUID(), UUID.randomUUID(), AMOUNT);

        service.refund(command, UUID.randomUUID(), UUID.randomUUID());
        service.refund(command, UUID.randomUUID(), UUID.randomUUID());

        verify(provider, times(1)).refund(any());
        assertThat(repositories.allOperations(OperationType.REFUND)).hasSize(1);
    }

    @Test
    void refund_withNoIntentForTheOrder_isANoOp()
    {
        service.refund(new PaymentService.RefundCommand(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AMOUNT), UUID.randomUUID(), UUID.randomUUID());

        verify(provider, never()).refund(any());
        verify(events, never()).refunded(any(), any(), any());
    }

    @Test
    void capture_withNoIntentForTheOrder_isANoOp()
    {
        service.capture(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

        verify(provider, never()).capture(any());
        verify(events, never()).captured(any(), any(), any());
        verify(events, never()).captureFailed(any(), any(), any());
    }

    @Test
    void noCardDataIsEverStored_onlyAnOpaqueToken()
    {
        UUID orderId = authorizedOrder(GOOD_TOKEN);

        PaymentIntentEntity intent = repositories.intents.findByOrderId(orderId).orElseThrow();
        // The only instrument reference anywhere in the model is the opaque provider token
        // (ADR-0016 §4, ADR-0009). There is no field a PAN could occupy.
        assertThat(intent.getPaymentMethodToken()).isEqualTo(GOOD_TOKEN);
        assertThat(intent.getPaymentMethodToken()).doesNotMatch(".*\\d{13,19}.*");
    }

    // ---------------------------------------------------------------------

    private PaymentService.AuthorizeCommand authorizeCommand(UUID orderId, String token)
    {
        return new PaymentService.AuthorizeCommand(orderId, UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), AMOUNT, token);
    }

    private UUID authorizedOrder(String token)
    {
        UUID orderId = UUID.randomUUID();
        service.authorize(authorizeCommand(orderId, token), UUID.randomUUID(), UUID.randomUUID());
        return orderId;
    }

    private PaymentOperationEntity onlyOperation(OperationType type)
    {
        List<PaymentOperationEntity> operations = repositories.allOperations(type);
        assertThat(operations).hasSize(1);
        return operations.get(0);
    }

    /**
     * In-memory stand-ins for the three repositories. Hand-written rather than Mockito stubs because
     * these tests exercise multi-step flows (authorize then capture then refund) where each step must
     * see what the previous one persisted — canned per-call answers could not represent that.
     */
    private static final class InMemoryRepositories
    {

        private final Map<UUID, PaymentIntentEntity> intentsById = new LinkedHashMap<>();
        private final Map<UUID, PaymentAttemptEntity> attemptsById = new LinkedHashMap<>();
        private final Map<UUID, PaymentOperationEntity> operationsById = new LinkedHashMap<>();

        private final PaymentIntentRepository intents = mock(PaymentIntentRepository.class);
        private final PaymentAttemptRepository attempts = mock(PaymentAttemptRepository.class);
        private final PaymentOperationRepository operations = mock(PaymentOperationRepository.class);

        InMemoryRepositories()
        {
            when(intents.save(any())).thenAnswer(i ->
            {
                PaymentIntentEntity intent = i.getArgument(0);
                intentsById.put(intent.getId(), intent);
                return intent;
            });
            when(intents.findByOrderId(any())).thenAnswer(i -> intentsById.values()
                    .stream()
                    .filter(intent -> intent.getOrderId().equals(i.getArgument(0)))
                    .findFirst());

            when(attempts.save(any())).thenAnswer(i ->
            {
                PaymentAttemptEntity attempt = i.getArgument(0);
                attemptsById.put(attempt.getId(), attempt);
                return attempt;
            });
            when(attempts.findById(any())).thenAnswer(i -> Optional.ofNullable(attemptsById.get(i.<UUID> getArgument(0))));
            when(attempts.findByIntentIdOrderByCreatedAtAsc(any())).thenAnswer(i -> attemptsById.values()
                    .stream()
                    .filter(attempt -> attempt.getIntent().getId().equals(i.getArgument(0)))
                    .sorted(Comparator.comparing(PaymentAttemptEntity::getCreatedAt))
                    .toList());

            when(operations.save(any())).thenAnswer(i ->
            {
                PaymentOperationEntity operation = i.getArgument(0);
                operationsById.put(operation.getId(), operation);
                return operation;
            });
            when(operations.findByProviderIdempotencyKey(any())).thenAnswer(i -> operationsById.values()
                    .stream()
                    .filter(operation -> operation.getProviderIdempotencyKey()
                            .equals(i.getArgument(0)))
                    .findFirst());
            when(operations.findByAttemptTypeAndStatus(any(), any(), any())).thenAnswer(i -> operationsById.values()
                    .stream()
                    .filter(operation -> operation.getAttempt().getId().equals(i.getArgument(0)))
                    .filter(operation -> operation.getOperationType() == i.getArgument(1))
                    .filter(operation -> operation.getStatus() == i.getArgument(2))
                    .toList());
        }

        List<PaymentOperationEntity> allOperations(OperationType type)
        {
            List<PaymentOperationEntity> matching = new ArrayList<>();
            for (PaymentOperationEntity operation : operationsById.values())
            {
                if (operation.getOperationType() == type)
                {
                    matching.add(operation);
                }
            }
            return matching;
        }
    }
}
