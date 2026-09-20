package com.sajidriaz.orderplatform.orderservice.repository;

import com.sajidriaz.orderplatform.orderservice.entity.OutboxRecordEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRecordRepository extends JpaRepository<OutboxRecordEntity, java.util.UUID>
{

    /**
     * Selects a batch of PENDING outbox rows for publishing, locking them so
     * concurrent relay instances (multiple replicas) never double-publish the same
     * row (ADR-0004). {@code SKIP LOCKED} means a row already claimed by another
     * replica is simply skipped rather than blocking this poll.
     */
    @Query (value = """
            select * from outbox
            where status = 'PENDING'
            order by created_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<OutboxRecordEntity> lockNextBatch(@Param ("batchSize") int batchSize);
}
