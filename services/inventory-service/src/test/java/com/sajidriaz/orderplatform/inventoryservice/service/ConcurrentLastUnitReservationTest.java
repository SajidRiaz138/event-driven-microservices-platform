package com.sajidriaz.orderplatform.inventoryservice.service;

import com.sajidriaz.orderplatform.common.messaging.MessageTypes;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.inventoryservice.cache.AvailabilityCache;
import com.sajidriaz.orderplatform.inventoryservice.cache.InMemoryAvailabilityCache;
import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationEntity;
import com.sajidriaz.orderplatform.inventoryservice.messaging.OutboxWriter;
import com.sajidriaz.orderplatform.inventoryservice.repository.ReservationRepository;
import com.sajidriaz.orderplatform.inventoryservice.repository.StockItemRepository;
import org.apache.avro.specific.SpecificRecordBase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Scenario S-6, no oversell on the last unit, at the service level with real threads.
 *
 * <p>What this proves and what it does not: the stock repository here is a fake whose
 * {@code tryReserve} implements the same semantics as the production SQL — a single atomic
 * compare-and-set of "take only if enough is available" — so this test shows the <em>service</em>
 * relies on that result and cannot be made to oversell by concurrency. It does not prove
 * PostgreSQL performs the real UPDATE atomically; that is proven against a real database in
 * {@code InventoryReservationIT.concurrentReservationsForTheLastUnit_exactlyOneSucceeds}. Both
 * matter: the SQL could be right while the service ignored its result, and vice versa.
 */
class ConcurrentLastUnitReservationTest {

    private static final String SKU = "SKU-LAST-UNIT";

    @Test
    void concurrentReservationsForTheLastUnit_exactlyOneSucceeds() throws Exception {
        int contenders = 16;
        AtomicSku stock = new AtomicSku(1);

        StockItemRepository stockItemRepository = fakeStockRepository(stock);
        ReservationRepository reservationRepository = mock(ReservationRepository.class);
        when(reservationRepository.findById(any())).thenReturn(Optional.empty());
        when(reservationRepository.findActiveByOrderId(any())).thenReturn(Optional.empty());
        when(reservationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        RecordingOutboxWriter outbox = new RecordingOutboxWriter();
        AvailabilityCache cache = new InMemoryAvailabilityCache();
        InventoryService service = new InventoryService(stockItemRepository, reservationRepository,
                outbox, cache, 900, 3600);

        CountDownLatch startLine = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(contenders);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        try {
            for (int i = 0; i < contenders; i++) {
                pool.submit(() -> {
                    try {
                        // Release them all at once: staggered starts would not contend.
                        startLine.await();
                        service.reserve(UUID.randomUUID(), UUID.randomUUID(),
                                List.of(new InventoryService.RequestedLine(SKU, 1)), 600,
                                UUID.randomUUID(), UUID.randomUUID());
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        finished.countDown();
                    }
                });
            }
            startLine.countDown();
            assertThat(finished.await(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        assertThat(outbox.count(MessageTypes.EVENT_INVENTORY_RESERVED))
                .as("exactly one of %d concurrent orders may take the single available unit", contenders)
                .isEqualTo(1);
        assertThat(outbox.count(MessageTypes.EVENT_INVENTORY_RESERVATION_FAILED))
                .isEqualTo(contenders - 1);

        // The invariant that actually matters: stock never went negative and never over-committed.
        assertThat(stock.reserved()).isEqualTo(1);
        assertThat(stock.available()).isZero();
    }

    /**
     * Mirrors {@code StockItemRepository.tryReserve}'s contract: one atomic step that takes the
     * quantity only if enough is available, returning the number of rows changed.
     */
    private StockItemRepository fakeStockRepository(AtomicSku stock) {
        StockItemRepository repository = mock(StockItemRepository.class);
        when(repository.existsById(anyString())).thenReturn(true);
        when(repository.tryReserve(anyString(), any(Integer.class)))
                .thenAnswer(invocation -> stock.tryReserve(invocation.getArgument(1)));
        when(repository.releaseReserved(anyString(), any(Integer.class)))
                .thenAnswer(invocation -> stock.release(invocation.getArgument(1)));
        return repository;
    }

    /** A single SKU's quantities behind a compare-and-set, standing in for the database row. */
    private static final class AtomicSku {

        private final int onHand;
        private final AtomicInteger reserved = new AtomicInteger();

        AtomicSku(int onHand) {
            this.onHand = onHand;
        }

        int tryReserve(int quantity) {
            while (true) {
                int current = reserved.get();
                if (onHand - current < quantity) {
                    return 0;
                }
                if (reserved.compareAndSet(current, current + quantity)) {
                    return 1;
                }
            }
        }

        int release(int quantity) {
            while (true) {
                int current = reserved.get();
                if (current < quantity) {
                    return 0;
                }
                if (reserved.compareAndSet(current, current - quantity)) {
                    return 1;
                }
            }
        }

        int reserved() {
            return reserved.get();
        }

        int available() {
            return onHand - reserved.get();
        }
    }

    /**
     * Counts emitted message types. A Mockito mock would do, but its invocation bookkeeping is not
     * designed for sixteen threads calling it at once, and a flaky concurrency test is worse than
     * none.
     */
    private static final class RecordingOutboxWriter extends OutboxWriter {

        private final Map<String, AtomicInteger> counts = new ConcurrentHashMap<>();

        RecordingOutboxWriter() {
            super(null, null);
        }

        @Override
        public void append(MessageKind kind, String type, String topic, UUID correlationId,
                           UUID causationId, UUID aggregateId, SpecificRecordBase payload) {
            counts.computeIfAbsent(type, key -> new AtomicInteger()).incrementAndGet();
        }

        int count(String type) {
            AtomicInteger counter = counts.get(type);
            return counter == null ? 0 : counter.get();
        }
    }
}
