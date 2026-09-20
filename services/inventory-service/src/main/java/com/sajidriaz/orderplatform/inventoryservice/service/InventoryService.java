package com.sajidriaz.orderplatform.inventoryservice.service;

import com.sajidriaz.orderplatform.common.messaging.MessageTypes;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.inventory.StockReleased;
import com.sajidriaz.orderplatform.events.inventory.StockReservationFailed;
import com.sajidriaz.orderplatform.events.inventory.StockReserved;
import com.sajidriaz.orderplatform.inventoryservice.cache.AvailabilityCache;
import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationEntity;
import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationLineEntity;
import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationStatus;
import com.sajidriaz.orderplatform.inventoryservice.messaging.OutboxWriter;
import com.sajidriaz.orderplatform.inventoryservice.repository.ReservationRepository;
import com.sajidriaz.orderplatform.inventoryservice.repository.StockItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The inventory domain service: reserve, release, commit and expire stock reservations, each in
 * one transaction that also appends the reply event to the outbox (ADR-0004), so a stock change
 * and the announcement of it can never disagree.
 *
 * <p>Two things here are load-bearing and worth reading closely:
 *
 * <ol>
 *   <li><strong>Reservation is decided by {@link StockItemRepository#tryReserve}'s return
 *       value</strong>, never by reading availability and then writing. That is what makes
 *       oversell impossible under concurrency (ADR-0015, scenario S-6).</li>
 *   <li><strong>Insufficient stock is a normal outcome, not an error.</strong> It commits a
 *       {@code StockReservationFailed} event rather than throwing, because throwing would roll
 *       back the transaction — taking the outbox row with it — and route the command to the DLQ,
 *       where a perfectly ordinary business answer does not belong (ADR-0006). This is why the
 *       partial-failure path explicitly un-reserves what it already took instead of relying on
 *       rollback to undo it.</li>
 * </ol>
 */
@Service
public class InventoryService {

    private static final Logger log = LoggerFactory.getLogger(InventoryService.class);

    private final StockItemRepository stockItemRepository;
    private final ReservationRepository reservationRepository;
    private final OutboxWriter outboxWriter;
    private final AvailabilityCache availabilityCache;
    private final int defaultTtlSeconds;
    private final int maxTtlSeconds;

    public InventoryService(StockItemRepository stockItemRepository,
                            ReservationRepository reservationRepository,
                            OutboxWriter outboxWriter,
                            AvailabilityCache availabilityCache,
                            @Value("${inventory.reservation.default-ttl-seconds:900}") int defaultTtlSeconds,
                            @Value("${inventory.reservation.max-ttl-seconds:3600}") int maxTtlSeconds) {
        this.stockItemRepository = stockItemRepository;
        this.reservationRepository = reservationRepository;
        this.outboxWriter = outboxWriter;
        this.availabilityCache = availabilityCache;
        this.defaultTtlSeconds = defaultTtlSeconds;
        this.maxTtlSeconds = maxTtlSeconds;
    }

    /** One requested line of a reservation. */
    public record RequestedLine(String sku, int quantity) {
    }

    /**
     * Handle a {@code ReserveStock} command: hold stock for an order and reply with
     * {@code StockReserved} or {@code StockReservationFailed}.
     */
    @Transactional
    public void reserve(UUID orderId, UUID reservationId, List<RequestedLine> requestedLines,
                        int ttlSeconds, UUID correlationId, UUID causationId) {

        // A reservation we have already made for this id: re-affirm it and change nothing. The
        // listener's processed_message check catches literal redelivery; this catches the
        // orchestrator re-issuing the command, which arrives as a new message id.
        Optional<ReservationEntity> byId = reservationRepository.findById(reservationId);
        if (byId.isPresent()) {
            ReservationEntity existing = byId.get();
            log.info("Duplicate ReserveStock for reservation {} (order {}, status {}); re-affirming",
                    reservationId, orderId, existing.getStatus());
            replyForExisting(existing, correlationId, causationId);
            return;
        }

        // An active reservation already exists for this order under a different id. This happens
        // because the orchestrator mints a fresh reservationId per attempt, so id equality cannot
        // be relied on to spot a retry — the order id can. Holding the stock twice for one order
        // would be a silent oversell, so re-affirm the existing hold instead.
        Optional<ReservationEntity> activeForOrder = reservationRepository.findActiveByOrderId(orderId);
        if (activeForOrder.isPresent()) {
            ReservationEntity existing = activeForOrder.get();
            log.info("Order {} already has active reservation {}; ignoring new reservationId {}",
                    orderId, existing.getId(), reservationId);
            replyForExisting(existing, correlationId, causationId);
            return;
        }

        Map<String, Integer> wanted = aggregateBySku(requestedLines);
        if (wanted.isEmpty()) {
            log.warn("ReserveStock for order {} has no lines; replying failed", orderId);
            emitReservationFailed(orderId, null, correlationId, causationId);
            return;
        }

        // SKUs are taken in a deterministic (sorted) order. Two concurrent multi-line
        // reservations touching the same pair of SKUs in opposite orders would otherwise be able
        // to deadlock on each other's row locks.
        List<Map.Entry<String, Integer>> taken = new ArrayList<>();
        String failedSku = null;
        for (Map.Entry<String, Integer> line : wanted.entrySet()) {
            if (!stockItemRepository.existsById(line.getKey())) {
                // An unknown SKU is a business failure for this order, not a poison message:
                // retrying or dead-lettering it would not make the SKU exist.
                failedSku = line.getKey();
                break;
            }
            if (stockItemRepository.tryReserve(line.getKey(), line.getValue()) == 0) {
                failedSku = line.getKey();
                break;
            }
            taken.add(line);
        }

        if (failedSku != null) {
            // Undo exactly what was taken. The transaction must COMMIT so the failure event
            // survives in the outbox, so rollback is not available as the undo mechanism.
            for (Map.Entry<String, Integer> line : taken) {
                stockItemRepository.releaseReserved(line.getKey(), line.getValue());
                availabilityCache.invalidate(line.getKey());
            }
            log.info("Reservation for order {} failed on sku {}", orderId, failedSku);
            emitReservationFailed(orderId, failedSku, correlationId, causationId);
            return;
        }

        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(effectiveTtlSeconds(ttlSeconds, orderId));
        ReservationEntity reservation = new ReservationEntity(reservationId, orderId, expiresAt, correlationId);
        wanted.forEach(reservation::addLine);
        reservationRepository.save(reservation);

        wanted.keySet().forEach(availabilityCache::invalidate);

        log.info("Reserved stock for order {} as reservation {} ({} line(s), expires {})",
                orderId, reservationId, wanted.size(), expiresAt);
        emitReserved(orderId, reservationId, now, expiresAt, correlationId, causationId);
    }

    /**
     * Handle a {@code ReleaseStock} command (saga compensation). Idempotent: releasing an already
     * released, expired or unknown reservation still replies {@code StockReleased}, because the
     * orchestrator's compensation only closes when that reply arrives and "there was nothing to
     * release" is a successful release.
     */
    @Transactional
    public void release(UUID orderId, UUID reservationId, UUID correlationId, UUID causationId) {
        // Prefer the id on the command, but fall back to the order's active reservation: the
        // orchestrator does not persist the reservationId this service issued, so the id on
        // ReleaseStock is a fresh random UUID that matches nothing. The order id always matches.
        ReservationEntity target = reservationRepository.findById(reservationId)
                .filter(r -> r.getOrderId().equals(orderId))
                .or(() -> reservationRepository.findActiveByOrderId(orderId))
                .orElse(null);

        if (target == null) {
            log.info("ReleaseStock for order {} (reservation {}) matched nothing; replying released",
                    orderId, reservationId);
            emitReleased(orderId, reservationId, correlationId, causationId);
            return;
        }

        if (target.getStatus() == ReservationStatus.COMMITTED) {
            // Post-pivot: this stock is sold. Un-reserving it would put sold goods back on the
            // shelf. The saga rolls forward after the pivot (ADR-0003), so this should not happen;
            // if it does it is an inconsistency for a human, not something to paper over.
            log.error("Refusing to release COMMITTED reservation {} for order {}: the stock is sold. "
                    + "Requires reconciliation.", target.getId(), orderId);
            return;
        }

        if (target.getStatus().isResolved()) {
            log.info("Reservation {} for order {} is already {}; replying released idempotently",
                    target.getId(), orderId, target.getStatus());
            emitReleased(orderId, target.getId(), correlationId, causationId);
            return;
        }

        releaseHeldStock(target, ReservationStatus.RELEASED);
        log.info("Released reservation {} for order {}", target.getId(), orderId);
        emitReleased(orderId, target.getId(), correlationId, causationId);
    }

    /**
     * The order was confirmed, so the sale completes: the held quantities leave both
     * {@code reserved} and {@code on_hand} and the reservation stops being subject to its TTL.
     *
     * <p>Driven by the existing {@code events.order.confirmed.v1} event rather than a new command.
     * Without this step a confirmed order's reservation would sit ACTIVE until its TTL fired and
     * the sweeper handed the stock back — returning goods that were already sold. There is no
     * "commit reservation" command in the saga contract, and the confirmation event carries
     * exactly the fact needed.
     */
    @Transactional
    public void commitForConfirmedOrder(UUID orderId) {
        Optional<ReservationEntity> active = reservationRepository.findActiveByOrderId(orderId);
        if (active.isEmpty()) {
            log.info("Order {} confirmed but has no active reservation (already committed, or never "
                    + "reserved here); nothing to commit", orderId);
            return;
        }
        ReservationEntity reservation = active.get();
        List<RequestedLine> lines = snapshotLines(reservation);

        reservation.resolve(ReservationStatus.COMMITTED, Instant.now());
        reservationRepository.save(reservation);

        for (RequestedLine line : lines) {
            if (stockItemRepository.commitReserved(line.sku(), line.quantity()) == 0) {
                // Conditional on reserved/on_hand, so 0 means the quantities are not what this
                // reservation believes. Loud, because it means stock accounting has drifted.
                log.error("Commit of {} x {} for reservation {} affected no row; stock accounting "
                        + "requires reconciliation", line.quantity(), line.sku(), reservation.getId());
            }
            availabilityCache.invalidate(line.sku());
        }
        log.info("Committed reservation {} for confirmed order {}", reservation.getId(), orderId);
    }

    /**
     * Release an expired reservation (TTL, ADR-0015) and announce it. Called by the sweeper for
     * rows it has already locked. {@code StockReleased} is the documented reply for a TTL expiry
     * as well as for an explicit release; the orchestrator treats one for a saga that has moved
     * on as a no-op.
     */
    @Transactional
    public void expire(ReservationEntity reservation) {
        if (!reservation.getStatus().isActive()) {
            return;
        }
        releaseHeldStock(reservation, ReservationStatus.EXPIRED);
        log.info("Reservation {} for order {} expired at {}; stock returned",
                reservation.getId(), reservation.getOrderId(), reservation.getExpiresAt());
        // No inbound message caused this, so there is no causationId; the correlation id stored
        // on the reservation keeps the event joined to the order's flow.
        emitReleased(reservation.getOrderId(), reservation.getId(), reservation.getCorrelationId(), null);
    }

    /** Availability straight from the database: {@code on_hand - reserved}. Never cached here. */
    @Transactional(readOnly = true)
    public Optional<Integer> availableFromDatabase(String sku) {
        return stockItemRepository.findAvailable(sku);
    }

    // ---------------------------------------------------------------------
    // internals
    // ---------------------------------------------------------------------

    /**
     * Resolve a reservation and hand its stock back. The reservation is saved <em>before</em> the
     * native stock updates run: those carry {@code flushAutomatically}/{@code clearAutomatically},
     * so the status change must already be flushed or clearing the persistence context would
     * discard it. The lines are snapshotted first for the same reason.
     */
    private void releaseHeldStock(ReservationEntity reservation, ReservationStatus resolution) {
        List<RequestedLine> lines = snapshotLines(reservation);
        reservation.resolve(resolution, Instant.now());
        reservationRepository.save(reservation);

        for (RequestedLine line : lines) {
            if (stockItemRepository.releaseReserved(line.sku(), line.quantity()) == 0) {
                log.error("Release of {} x {} for reservation {} affected no row; stock accounting "
                        + "requires reconciliation", line.quantity(), line.sku(), reservation.getId());
            }
            availabilityCache.invalidate(line.sku());
        }
    }

    private List<RequestedLine> snapshotLines(ReservationEntity reservation) {
        List<RequestedLine> lines = new ArrayList<>();
        for (ReservationLineEntity line : reservation.getLines()) {
            lines.add(new RequestedLine(line.getSku(), line.getQuantity()));
        }
        return lines;
    }

    private void replyForExisting(ReservationEntity existing, UUID correlationId, UUID causationId) {
        if (existing.getStatus().isActive() || existing.getStatus() == ReservationStatus.COMMITTED) {
            emitReserved(existing.getOrderId(), existing.getId(), existing.getCreatedAt(),
                    existing.getExpiresAt(), correlationId, causationId);
        } else {
            // It was reserved once and has since been released or expired. Re-asserting
            // StockReserved would tell the orchestrator stock is held when it is not.
            emitReservationFailed(existing.getOrderId(), null, correlationId, causationId);
        }
    }

    /** Sums duplicate lines for the same SKU, and keeps SKUs in a deterministic lock order. */
    private Map<String, Integer> aggregateBySku(List<RequestedLine> lines) {
        Map<String, Integer> aggregated = new TreeMap<>();
        for (RequestedLine line : lines) {
            if (line.quantity() <= 0) {
                throw new IllegalArgumentException(
                        "quantity must be > 0 for sku " + line.sku() + ": " + line.quantity());
            }
            aggregated.merge(line.sku(), line.quantity(), Integer::sum);
        }
        return new LinkedHashMap<>(aggregated);
    }

    private int effectiveTtlSeconds(int requested, UUID orderId) {
        if (requested <= 0) {
            log.warn("ReserveStock for order {} carried ttlSeconds={}; using default {}s",
                    orderId, requested, defaultTtlSeconds);
            return defaultTtlSeconds;
        }
        if (requested > maxTtlSeconds) {
            // A reservation held far longer than intended is stock nobody can sell; cap it
            // rather than trusting an arbitrary number from the wire.
            log.warn("ReserveStock for order {} requested ttlSeconds={}, capping at {}s",
                    orderId, requested, maxTtlSeconds);
            return maxTtlSeconds;
        }
        return requested;
    }

    private void emitReserved(UUID orderId, UUID reservationId, Instant reservedAt, Instant expiresAt,
                              UUID correlationId, UUID causationId) {
        StockReserved payload = StockReserved.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .setReservedAt(reservedAt)
                .setExpiresAt(expiresAt)
                .build();
        outboxWriter.append(MessageKind.EVENT, MessageTypes.EVENT_INVENTORY_RESERVED,
                PlatformTopics.EVENTS_INVENTORY_RESERVED, correlationId, causationId, orderId, payload);
    }

    private void emitReservationFailed(UUID orderId, String failedSku, UUID correlationId, UUID causationId) {
        StockReservationFailed payload = StockReservationFailed.newBuilder()
                .setOrderId(orderId)
                // The SKU that could not be satisfied, never the quantity: remaining stock levels
                // are not leaked outside this service (schema doc, ADR-0015).
                .setFailedSku(failedSku)
                .setFailedAt(Instant.now())
                .build();
        outboxWriter.append(MessageKind.EVENT, MessageTypes.EVENT_INVENTORY_RESERVATION_FAILED,
                PlatformTopics.EVENTS_INVENTORY_RESERVATION_FAILED, correlationId, causationId,
                orderId, payload);
    }

    private void emitReleased(UUID orderId, UUID reservationId, UUID correlationId, UUID causationId) {
        StockReleased payload = StockReleased.newBuilder()
                .setOrderId(orderId)
                .setReservationId(reservationId)
                .setReleasedAt(Instant.now())
                .build();
        outboxWriter.append(MessageKind.EVENT, MessageTypes.EVENT_INVENTORY_RELEASED,
                PlatformTopics.EVENTS_INVENTORY_RELEASED, correlationId, causationId, orderId, payload);
    }
}
