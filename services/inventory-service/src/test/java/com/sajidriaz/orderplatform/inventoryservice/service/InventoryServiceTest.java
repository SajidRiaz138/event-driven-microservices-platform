package com.sajidriaz.orderplatform.inventoryservice.service;

import com.sajidriaz.orderplatform.common.messaging.MessageTypes;
import com.sajidriaz.orderplatform.common.messaging.PlatformTopics;
import com.sajidriaz.orderplatform.events.MessageKind;
import com.sajidriaz.orderplatform.events.inventory.StockReleased;
import com.sajidriaz.orderplatform.events.inventory.StockReservationFailed;
import com.sajidriaz.orderplatform.events.inventory.StockReserved;
import com.sajidriaz.orderplatform.inventoryservice.cache.AvailabilityCache;
import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationEntity;
import com.sajidriaz.orderplatform.inventoryservice.entity.ReservationStatus;
import com.sajidriaz.orderplatform.inventoryservice.messaging.OutboxWriter;
import com.sajidriaz.orderplatform.inventoryservice.repository.ReservationRepository;
import com.sajidriaz.orderplatform.inventoryservice.repository.StockItemRepository;
import org.apache.avro.specific.SpecificRecordBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith (MockitoExtension.class)
@MockitoSettings (strictness = Strictness.LENIENT)
class InventoryServiceTest
{

    private static final int DEFAULT_TTL = 900;
    private static final int MAX_TTL = 3600;

    @Mock
    private StockItemRepository stockItemRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private OutboxWriter outboxWriter;
    @Mock
    private AvailabilityCache availabilityCache;

    private InventoryService service;

    private final UUID orderId = UUID.randomUUID();
    private final UUID reservationId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final UUID causationId = UUID.randomUUID();

    @BeforeEach
    void setUp()
    {
        service = new InventoryService(stockItemRepository, reservationRepository, outboxWriter,
                availabilityCache, DEFAULT_TTL, MAX_TTL);
        when(reservationRepository.findById(any())).thenReturn(Optional.empty());
        when(reservationRepository.findActiveByOrderId(any())).thenReturn(Optional.empty());
        when(stockItemRepository.existsById(anyString())).thenReturn(true);
    }

    @Test
    void reserve_whenStockIsAvailable_savesReservationAndEmitsStockReserved()
    {
        when(stockItemRepository.tryReserve("SKU-1001", 2)).thenReturn(1);

        service.reserve(orderId, reservationId, List.of(line("SKU-1001", 2)), 600,
                correlationId, causationId);

        ArgumentCaptor<ReservationEntity> saved = ArgumentCaptor.forClass(ReservationEntity.class);
        verify(reservationRepository).save(saved.capture());
        assertThat(saved.getValue().getId()).isEqualTo(reservationId);
        assertThat(saved.getValue().getOrderId()).isEqualTo(orderId);
        assertThat(saved.getValue().getStatus()).isEqualTo(ReservationStatus.ACTIVE);
        assertThat(saved.getValue().getCorrelationId()).isEqualTo(correlationId);
        assertThat(saved.getValue().getLines()).hasSize(1);

        StockReserved event = capturedEvent(StockReserved.class,
                MessageTypes.EVENT_INVENTORY_RESERVED, PlatformTopics.EVENTS_INVENTORY_RESERVED);
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getReservationId()).isEqualTo(reservationId);
        assertThat(event.getExpiresAt()).isAfter(event.getReservedAt());

        verify(availabilityCache).invalidate("SKU-1001");
    }

    @Test
    void reserve_isDecidedByTheConditionalUpdateResultNotByReadingAvailability()
    {
        // The atomic UPDATE reports it changed nothing — the last unit went to someone else
        // between any read and this write. The service must fail the reservation on that result
        // alone. A read-then-write implementation would have "seen" stock and oversold.
        when(stockItemRepository.tryReserve("SKU-1001", 1)).thenReturn(0);

        service.reserve(orderId, reservationId, List.of(line("SKU-1001", 1)), 600,
                correlationId, causationId);

        verify(reservationRepository, never()).save(any());
        StockReservationFailed event = capturedEvent(StockReservationFailed.class,
                MessageTypes.EVENT_INVENTORY_RESERVATION_FAILED,
                PlatformTopics.EVENTS_INVENTORY_RESERVATION_FAILED);
        assertThat(event.getOrderId()).isEqualTo(orderId);
        assertThat(event.getFailedSku()).isEqualTo("SKU-1001");
    }

    @Test
    void reserve_neverConsultsTheCacheForTheDecision()
    {
        when(stockItemRepository.tryReserve(anyString(), anyInt())).thenReturn(1);

        service.reserve(orderId, reservationId, List.of(line("SKU-1001", 1)), 600,
                correlationId, causationId);

        // The cache is invalidated (a write), but never read: a stale availability number must
        // never influence whether stock is held (ADR-0015).
        verify(availabilityCache, never()).get(anyString());
        verify(availabilityCache).invalidate("SKU-1001");
    }

    @Test
    void reserve_whenALaterLineFails_releasesTheLinesAlreadyTaken()
    {
        // SKUs are processed in sorted order, so SKU-1001 is taken before SKU-1002 fails.
        when(stockItemRepository.tryReserve("SKU-1001", 1)).thenReturn(1);
        when(stockItemRepository.tryReserve("SKU-1002", 5)).thenReturn(0);

        service.reserve(orderId, reservationId, List.of(line("SKU-1002", 5), line("SKU-1001", 1)),
                600, correlationId, causationId);

        // The transaction commits (the failure event must survive in the outbox), so the partial
        // hold has to be undone explicitly rather than by rollback.
        verify(stockItemRepository).releaseReserved("SKU-1001", 1);
        verify(stockItemRepository, never()).releaseReserved("SKU-1002", 5);
        verify(reservationRepository, never()).save(any());
        assertThat(capturedEvent(StockReservationFailed.class,
                MessageTypes.EVENT_INVENTORY_RESERVATION_FAILED,
                PlatformTopics.EVENTS_INVENTORY_RESERVATION_FAILED).getFailedSku()).isEqualTo("SKU-1002");
    }

    @Test
    void reserve_duplicateReservationId_holdsNoExtraStockAndReAffirmsTheReply()
    {
        ReservationEntity existing = activeReservation(reservationId, 2);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(existing));

        service.reserve(orderId, reservationId, List.of(line("SKU-1001", 2)), 600,
                correlationId, causationId);

        verifyNoInteractions(stockItemRepository);
        verify(reservationRepository, never()).save(any());
        // The reply is re-sent: the orchestrator may have re-issued the command precisely because
        // it never saw the first reply.
        assertThat(capturedEvent(StockReserved.class, MessageTypes.EVENT_INVENTORY_RESERVED,
                PlatformTopics.EVENTS_INVENTORY_RESERVED).getReservationId()).isEqualTo(reservationId);
    }

    @Test
    void reserve_whenOrderAlreadyHasAnActiveReservationUnderAnotherId_doesNotDoubleHold()
    {
        // The orchestrator mints a fresh reservationId per attempt, so a retry arrives with an id
        // this service has never seen. Reserving again would silently hold the stock twice.
        UUID otherReservationId = UUID.randomUUID();
        ReservationEntity existing = activeReservation(otherReservationId, 2);
        when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.of(existing));

        service.reserve(orderId, reservationId, List.of(line("SKU-1001", 2)), 600,
                correlationId, causationId);

        verifyNoInteractions(stockItemRepository);
        assertThat(capturedEvent(StockReserved.class, MessageTypes.EVENT_INVENTORY_RESERVED,
                PlatformTopics.EVENTS_INVENTORY_RESERVED).getReservationId())
                .isEqualTo(otherReservationId);
    }

    @Test
    void reserve_ttlIsDefaultedWhenAbsentAndCappedWhenImplausible()
    {
        when(stockItemRepository.tryReserve(anyString(), anyInt())).thenReturn(1);

        service.reserve(orderId, reservationId, List.of(line("SKU-1001", 1)), 0,
                correlationId, causationId);
        service.reserve(orderId, UUID.randomUUID(), List.of(line("SKU-1001", 1)), 999_999,
                correlationId, causationId);

        ArgumentCaptor<ReservationEntity> saved = ArgumentCaptor.forClass(ReservationEntity.class);
        verify(reservationRepository, times(2)).save(saved.capture());
        Instant defaulted = saved.getAllValues().get(0).getExpiresAt();
        Instant capped = saved.getAllValues().get(1).getExpiresAt();
        assertThat(defaulted).isBefore(Instant.now().plusSeconds(DEFAULT_TTL + 5));
        assertThat(capped).isBefore(Instant.now().plusSeconds(MAX_TTL + 5));
    }

    @Test
    void release_returnsStockAndEmitsStockReleased()
    {
        ReservationEntity existing = activeReservation(reservationId, 3);
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(existing));
        when(stockItemRepository.releaseReserved("SKU-1001", 3)).thenReturn(1);

        service.release(orderId, reservationId, correlationId, causationId);

        verify(stockItemRepository).releaseReserved("SKU-1001", 3);
        assertThat(existing.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(existing.getResolvedAt()).isNotNull();
        verify(availabilityCache).invalidate("SKU-1001");
        assertThat(capturedEvent(StockReleased.class, MessageTypes.EVENT_INVENTORY_RELEASED,
                PlatformTopics.EVENTS_INVENTORY_RELEASED).getReservationId()).isEqualTo(reservationId);
    }

    @Test
    void release_fallsBackToTheOrdersActiveReservationWhenTheCommandsIdMatchesNothing()
    {
        // ReleaseStock.reservationId from the orchestrator is a fresh random UUID: it does not
        // persist the id this service issued. The order id is the only reliable handle.
        UUID unknownId = UUID.randomUUID();
        ReservationEntity existing = activeReservation(UUID.randomUUID(), 1);
        when(reservationRepository.findById(unknownId)).thenReturn(Optional.empty());
        when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.of(existing));
        when(stockItemRepository.releaseReserved("SKU-1001", 1)).thenReturn(1);

        service.release(orderId, unknownId, correlationId, causationId);

        verify(stockItemRepository).releaseReserved("SKU-1001", 1);
        assertThat(existing.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void release_isIdempotent_alreadyReleasedStillRepliesWithoutTouchingStock()
    {
        ReservationEntity existing = activeReservation(reservationId, 3);
        existing.resolve(ReservationStatus.RELEASED, Instant.now());
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(existing));

        service.release(orderId, reservationId, correlationId, causationId);

        verify(stockItemRepository, never()).releaseReserved(anyString(), anyInt());
        // The reply still goes out: the orchestrator's compensation only closes when it arrives.
        assertThat(capturedEvent(StockReleased.class, MessageTypes.EVENT_INVENTORY_RELEASED,
                PlatformTopics.EVENTS_INVENTORY_RELEASED)).isNotNull();
    }

    @Test
    void release_whenNothingMatchesAtAll_stillRepliesReleased()
    {
        service.release(orderId, UUID.randomUUID(), correlationId, causationId);

        verify(stockItemRepository, never()).releaseReserved(anyString(), anyInt());
        assertThat(capturedEvent(StockReleased.class, MessageTypes.EVENT_INVENTORY_RELEASED,
                PlatformTopics.EVENTS_INVENTORY_RELEASED).getOrderId()).isEqualTo(orderId);
    }

    @Test
    void release_refusesToUnsellACommittedReservation()
    {
        ReservationEntity existing = activeReservation(reservationId, 3);
        existing.resolve(ReservationStatus.COMMITTED, Instant.now());
        when(reservationRepository.findById(reservationId)).thenReturn(Optional.of(existing));

        service.release(orderId, reservationId, correlationId, causationId);

        // Committed stock is sold. Releasing it would put sold goods back on the shelf, so this
        // path deliberately does nothing and emits nothing — it needs reconciliation, not a reply.
        verify(stockItemRepository, never()).releaseReserved(anyString(), anyInt());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void commitForConfirmedOrder_removesStockFromOnHandAndEmitsNothing()
    {
        ReservationEntity existing = activeReservation(reservationId, 2);
        when(reservationRepository.findActiveByOrderId(orderId)).thenReturn(Optional.of(existing));
        when(stockItemRepository.commitReserved("SKU-1001", 2)).thenReturn(1);

        service.commitForConfirmedOrder(orderId);

        verify(stockItemRepository).commitReserved("SKU-1001", 2);
        assertThat(existing.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        verify(availabilityCache).invalidate("SKU-1001");
        // There is no "stock committed" event in the contract, and inventing one would be a new
        // wire message nobody consumes.
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void commitForConfirmedOrder_withNoActiveReservationIsANoOp()
    {
        service.commitForConfirmedOrder(orderId);

        verify(stockItemRepository, never()).commitReserved(anyString(), anyInt());
        verifyNoInteractions(outboxWriter);
    }

    @Test
    void expire_returnsStockAndAnnouncesReleaseUsingTheStoredCorrelationId()
    {
        ReservationEntity existing = activeReservation(reservationId, 4);
        when(stockItemRepository.releaseReserved("SKU-1001", 4)).thenReturn(1);

        service.expire(existing);

        verify(stockItemRepository).releaseReserved("SKU-1001", 4);
        assertThat(existing.getStatus()).isEqualTo(ReservationStatus.EXPIRED);

        ArgumentCaptor<UUID> correlation = ArgumentCaptor.forClass(UUID.class);
        verify(outboxWriter).append(eq(MessageKind.EVENT), eq(MessageTypes.EVENT_INVENTORY_RELEASED),
                eq(PlatformTopics.EVENTS_INVENTORY_RELEASED), correlation.capture(),
                eq(null), eq(orderId), any(SpecificRecordBase.class));
        // No inbound message caused this, so causationId is null and the correlation id comes from
        // the reservation — otherwise the expiry event would be an orphan in the traces.
        assertThat(correlation.getValue()).isEqualTo(correlationId);
    }

    @Test
    void expire_onAnAlreadyResolvedReservationDoesNothing()
    {
        ReservationEntity existing = activeReservation(reservationId, 4);
        existing.resolve(ReservationStatus.RELEASED, Instant.now());

        service.expire(existing);

        verify(stockItemRepository, never()).releaseReserved(anyString(), anyInt());
        verifyNoInteractions(outboxWriter);
    }

    // ---------------------------------------------------------------------

    private InventoryService.RequestedLine line(String sku, int quantity)
    {
        return new InventoryService.RequestedLine(sku, quantity);
    }

    private ReservationEntity activeReservation(UUID id, int quantity)
    {
        ReservationEntity reservation =
                new ReservationEntity(id, orderId, Instant.now().plusSeconds(900), correlationId);
        reservation.addLine("SKU-1001", quantity);
        return reservation;
    }

    private <T extends SpecificRecordBase> T capturedEvent(Class<T> type,
                                                           String expectedMessageType,
                                                           String expectedTopic)
    {
        ArgumentCaptor<SpecificRecordBase> payload = ArgumentCaptor.forClass(SpecificRecordBase.class);
        verify(outboxWriter).append(eq(MessageKind.EVENT), eq(expectedMessageType), eq(expectedTopic),
                any(), any(), eq(orderId), payload.capture());
        assertThat(payload.getValue()).isInstanceOf(type);
        return type.cast(payload.getValue());
    }
}
