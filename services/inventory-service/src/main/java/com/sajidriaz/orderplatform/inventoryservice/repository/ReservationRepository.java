package com.sajidriaz.orderplatform.inventoryservice.repository;

import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReservationRepository extends JpaRepository<ReservationEntity, UUID> {

    /**
     * The active reservation for an order, if any.
     *
     * <p>This is the correlation handle the service actually relies on, because the
     * orchestrator's {@code ReleaseStock.reservationId} is a freshly-minted random UUID rather
     * than the id this service issued (it does not persist the id returned in
     * {@code StockReserved}). The order id, carried as the envelope's {@code aggregateId}, is
     * the only identifier that is consistent across the whole flow. A partial unique index
     * guarantees at most one row can match.
     */
    @Query("select r from ReservationEntity r where r.orderId = :orderId and r.status = com.sajidriaz.orderplatform.inventoryservice.entity.ReservationStatus.ACTIVE")
    Optional<ReservationEntity> findActiveByOrderId(@Param("orderId") UUID orderId);

    List<ReservationEntity> findByOrderId(UUID orderId);

    /**
     * Claims a batch of expired reservations for the TTL sweeper. {@code FOR UPDATE SKIP
     * LOCKED} lets several replicas sweep concurrently without two of them releasing the same
     * reservation (ADR-0004's relay pattern applied to expiry).
     */
    @Query(value = """
            select * from inventory.reservation
            where status = 'ACTIVE'
              and expires_at < :now
            order by expires_at
            limit :batchSize
            for update skip locked
            """, nativeQuery = true)
    List<ReservationEntity> lockExpired(@Param("now") Instant now, @Param("batchSize") int batchSize);
}
