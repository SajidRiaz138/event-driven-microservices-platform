package com.sajidriaz.orderplatform.inventoryservice.web;

import com.sajidriaz.orderplatform.inventoryservice.service.AvailabilityQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Availability read for display (ADR-0007's display-only cache read path).
 *
 * <p>SECURITY: this endpoint is unauthenticated. That is a deliberate Phase-1 position matching
 * the rest of the platform — the gateway and auth-service (ADR-0009/ADR-0018) are not built yet,
 * so there is no token to validate. It exposes only a per-SKU availability number, which is
 * catalog-level information, and it is read-only. It must not be exposed publicly as-is: put it
 * behind the gateway with rate limiting when that lands, since an open, uncapped inventory probe
 * is both a scraping surface and a cheap way to load the database.
 *
 * <p>The number served here is a cached, possibly-stale display value. It is explicitly not a
 * promise that a reservation will succeed — only the atomic conditional UPDATE decides that
 * (ADR-0015).
 */
@RestController
@RequestMapping("/api/v1/inventory")
public class AvailabilityController {

    private final AvailabilityQueryService availabilityQueryService;

    public AvailabilityController(AvailabilityQueryService availabilityQueryService) {
        this.availabilityQueryService = availabilityQueryService;
    }

    /** Availability for a SKU, or 404 when the SKU is unknown. */
    @GetMapping("/availability/{sku}")
    public ResponseEntity<AvailabilityResponse> availability(@PathVariable String sku) {
        return availabilityQueryService.availableForDisplay(sku)
                .map(available -> ResponseEntity.ok(new AvailabilityResponse(sku, available)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * @param sku       the SKU queried
     * @param available display-only availability; not a reservation guarantee
     */
    public record AvailabilityResponse(String sku, int available) {
    }
}
