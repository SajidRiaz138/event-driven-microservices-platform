package com.sajidriaz.orderplatform.orderservice.pricing;

import com.sajidriaz.orderplatform.common.money.Money;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;

/**
 * Fixed in-code {@link PriceCatalog} for Phase 1 (no catalog/pricing service exists
 * yet). SKUs and prices below are demo data mirroring the OpenAPI examples
 * (SKU-1001 = 19.99). Every price is quoted in USD; a currency mismatch on the
 * request is a validation failure (422), not a silent conversion.
 */
@Component
public class InMemoryPriceCatalog implements PriceCatalog {

    private static final String CATALOG_CURRENCY = "USD";

    private static final Map<String, Long> PRICES_MINOR_UNITS = Map.of(
            "SKU-1001", 1999L,
            "SKU-1002", 2999L,
            "SKU-1003", 999L,
            "SKU-2001", 4999L,
            "SKU-2002", 12999L
    );

    @Override
    public Optional<Money> unitPriceFor(String sku, String currency) {
        if (!CATALOG_CURRENCY.equals(currency)) {
            return Optional.empty();
        }
        Long minorUnits = PRICES_MINOR_UNITS.get(sku);
        if (minorUnits == null) {
            return Optional.empty();
        }
        return Optional.of(new Money(minorUnits, currency));
    }
}
