package com.sajidriaz.orderplatform.inventoryservice.service;

import com.sajidriaz.orderplatform.inventoryservice.cache.AvailabilityCache;
import com.sajidriaz.orderplatform.inventoryservice.cache.InMemoryAvailabilityCache;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AvailabilityQueryServiceTest {

    private InventoryService inventoryService;
    private AvailabilityCache cache;
    private AvailabilityQueryService service;

    @BeforeEach
    void setUp() {
        inventoryService = mock(InventoryService.class);
        cache = new InMemoryAvailabilityCache();
        service = new AvailabilityQueryService(inventoryService, cache, new SimpleMeterRegistry());
    }

    @Test
    void firstReadMissesAndPopulatesTheCache_secondIsServedFromIt() {
        when(inventoryService.availableFromDatabase("SKU-1001")).thenReturn(Optional.of(7));

        assertThat(service.availableForDisplay("SKU-1001")).contains(7);
        assertThat(service.availableForDisplay("SKU-1001")).contains(7);

        // One database read for two display reads: that is the point of cache-aside.
        verify(inventoryService, times(1)).availableFromDatabase("SKU-1001");
        assertThat(cache.get("SKU-1001")).contains(7);
    }

    @Test
    void anUnknownSkuIsNotCached() {
        when(inventoryService.availableFromDatabase("SKU-NOPE")).thenReturn(Optional.empty());

        assertThat(service.availableForDisplay("SKU-NOPE")).isEmpty();
        assertThat(cache.get("SKU-NOPE")).isEmpty();

        // Caching absence would need its own invalidation the moment the SKU is created.
        assertThat(service.availableForDisplay("SKU-NOPE")).isEmpty();
        verify(inventoryService, times(2)).availableFromDatabase("SKU-NOPE");
    }

    @Test
    void invalidationMakesTheNextReadGoBackToTheDatabase() {
        when(inventoryService.availableFromDatabase("SKU-1001"))
                .thenReturn(Optional.of(7))
                .thenReturn(Optional.of(5));

        assertThat(service.availableForDisplay("SKU-1001")).contains(7);
        cache.invalidate("SKU-1001");

        assertThat(service.availableForDisplay("SKU-1001")).contains(5);
        verify(inventoryService, times(2)).availableFromDatabase("SKU-1001");
    }

    @Test
    void aCacheThatFailsEveryOperationStillYieldsCorrectReads() {
        // A broken cache must degrade to reading the database, never fail the request: this read is
        // display-only and does not justify an outage.
        AvailabilityCache brokenCache = mock(AvailabilityCache.class);
        when(brokenCache.kind()).thenReturn("broken");
        when(brokenCache.get(anyString())).thenReturn(Optional.empty());
        AvailabilityQueryService withBrokenCache =
                new AvailabilityQueryService(inventoryService, brokenCache, new SimpleMeterRegistry());
        when(inventoryService.availableFromDatabase("SKU-1001")).thenReturn(Optional.of(3));

        assertThat(withBrokenCache.availableForDisplay("SKU-1001")).contains(3);
    }
}
