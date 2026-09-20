package com.sajidriaz.orderplatform.inventoryservice.service;

import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationEntity;
import com.sajidriaz.orderplatform.inventoryservice.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Returns stock held by reservations whose TTL has passed (ADR-0015).
 *
 * <p>This is what makes the TTL real rather than decorative: if the orchestrator dies between
 * reserving and paying, nothing else will ever release that stock, and an abandoned hold is
 * indistinguishable from sold inventory. Claims rows with {@code FOR UPDATE SKIP LOCKED} so
 * several replicas can sweep at once without two of them releasing the same reservation.
 */
@Component
public class ReservationExpirySweeper {

    private static final Logger log = LoggerFactory.getLogger(ReservationExpirySweeper.class);

    private final ReservationRepository reservationRepository;
    private final InventoryService inventoryService;
    private final int batchSize;

    public ReservationExpirySweeper(ReservationRepository reservationRepository,
                                    InventoryService inventoryService,
                                    @Value("${inventory.reservation.expiry-sweep.batch-size:50}") int batchSize) {
        this.reservationRepository = reservationRepository;
        this.inventoryService = inventoryService;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${inventory.reservation.expiry-sweep.fixed-delay-ms:1000}")
    @Transactional
    public void sweepExpiredReservations() {
        List<ReservationEntity> expired = reservationRepository.lockExpired(Instant.now(), batchSize);
        if (expired.isEmpty()) {
            return;
        }
        log.info("Expiring {} reservation(s) past their TTL", expired.size());
        for (ReservationEntity reservation : expired) {
            inventoryService.expire(reservation);
        }
    }
}
