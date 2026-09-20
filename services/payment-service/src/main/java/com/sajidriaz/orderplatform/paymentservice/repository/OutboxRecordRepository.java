package com.sajidriaz.orderplatform.paymentservice.repository;

import com.sajidriaz.orderplatform.paymentservice.entity.OutboxRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface OutboxRecordRepository extends JpaRepository<OutboxRecordEntity, UUID>
{

    /**
     * Selects a batch of PENDING outbox rows for publishing, locking them so concurrent relay
     * instances (multiple replicas) never double-publish the same row (ADR-0004).
     * {@code SKIP LOCKED} means a row already claimed by another replica is skipped rather than
     * blocking this poll.
     */
    @Query (value = """
            select * from payment.outbox
            where status = 'PENDING'
            order by created_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<OutboxRecordEntity> lockNextBatch(@Param ("batchSize") int batchSize);

    /** Unpublished backlog — the outbox-lag gauge ADR-0013 asks for. */
    long countByStatus(com.sajidriaz.orderplatform.common.outbox.OutboxStatus status);
}
