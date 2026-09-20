package com.sajidriaz.orderplatform.orderservice.repository;

import com.sajidriaz.orderplatform.orderservice.entity.SagaInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SagaInstanceRepository extends JpaRepository<SagaInstanceEntity, UUID>
{

    /**
     * Pessimistic write lock while a reply event is being applied — prevents two
     * concurrently-processed replies for the same order from racing on the saga
     * state machine (belt-and-suspenders alongside optimistic locking).
     */
    @Lock (LockModeType.PESSIMISTIC_WRITE)
    @Query ("select s from SagaInstanceEntity s where s.orderId = :orderId")
    Optional<SagaInstanceEntity> findByIdForUpdate(UUID orderId);

    /**
     * Claims a batch of sagas whose step deadline has passed and which are still in
     * flight, for timeout handling (ADR-0003). Uses {@code FOR UPDATE SKIP LOCKED} for
     * the same reason the outbox relay does (ADR-0004): several replicas may sweep
     * concurrently, and each expired saga must be handled exactly once per sweep rather
     * than blocking behind another replica's lock.
     *
     * <p>Terminal sagas are excluded in SQL as well as by the deadline being nulled on
     * every terminal transition — belt and braces, since a swept-and-cancelled saga must
     * never be reconsidered.
     */
    @Query (value = """
            select * from saga_instance
            where deadline is not null
              and deadline < :now
              and status not in ('CONFIRMED', 'CANCELLED')
            order by deadline
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<SagaInstanceEntity> lockExpiredDeadlines(Instant now, int batchSize);
}
