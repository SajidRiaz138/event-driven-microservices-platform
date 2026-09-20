package com.sajidriaz.orderplatform.orderservice.saga;

import com.sajidriaz.orderplatform.orderservice.entity.SagaInstanceEntity;
import com.sajidriaz.orderplatform.orderservice.repository.SagaInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * The timer behind ADR-0003's "timeouts are timer-driven against the persisted deadline".
 * Polls {@code saga_instance} for in-flight sagas whose step deadline has passed and
 * hands each to {@link SagaOrchestrator#onStepTimeout}, which owns the actual policy
 * (compensate pre-pivot; never cancel at the capture pivot).
 *
 * <p>Mirrors the outbox relay's shape on purpose: a {@code @Scheduled} poll plus
 * {@code SELECT ... FOR UPDATE SKIP LOCKED}, so running several replicas neither
 * double-handles a timeout nor serialises replicas behind one another (ADR-0004).
 *
 * <p>Without this, a participant that never replies (payment-service down, a lost
 * command) left the saga in flight forever: the deadline column was written by nobody
 * and read by nobody, and {@code CancellationReason.ORDER_TIMEOUT} was unreachable.
 */
@Component
public class SagaTimeoutSweeper
{

    private static final Logger log = LoggerFactory.getLogger(SagaTimeoutSweeper.class);

    private final SagaInstanceRepository sagaInstanceRepository;
    private final SagaOrchestrator sagaOrchestrator;
    private final int batchSize;

    public SagaTimeoutSweeper(SagaInstanceRepository sagaInstanceRepository,
                              SagaOrchestrator sagaOrchestrator,
                              @Value ("${order-platform.saga.timeout-sweep.batch-size:50}") int batchSize)
    {
        this.sagaInstanceRepository = sagaInstanceRepository;
        this.sagaOrchestrator = sagaOrchestrator;
        this.batchSize = batchSize;
    }

    @Scheduled (fixedDelayString = "${order-platform.saga.timeout-sweep.fixed-delay-ms:1000}")
    @Transactional
    public void sweepExpiredDeadlines()
    {
        List<SagaInstanceEntity> expired = sagaInstanceRepository.lockExpiredDeadlines(Instant.now(), batchSize);
        if (expired.isEmpty())
        {
            return;
        }
        log.info("Sweeping {} saga(s) past their step deadline", expired.size());
        for (SagaInstanceEntity saga : expired)
        {
            sagaOrchestrator.onStepTimeout(saga.getOrderId());
        }
    }
}
