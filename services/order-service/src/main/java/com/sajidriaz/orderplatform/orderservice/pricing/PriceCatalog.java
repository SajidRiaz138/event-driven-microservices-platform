package com.sajidriaz.orderplatform.orderservice.pricing;

import com.sajidriaz.orderplatform.common.money.Money;

import java.util.Optional;

/**
 * Server-side price resolution (REST-API-GUIDE §1 "Pricing"). Clients send only
 * {@code sku} + {@code quantity}; order-service resolves the authoritative unit price
 * here and persists a price snapshot on the order line. This prevents client price
 * tampering (the vulnerability in the previous CRUD implementation, which accepted a
 * client-supplied price).
 *
 * <p>Phase 1 stand-in: a fixed in-code catalog. A real catalog/pricing service (or a
 * server-issued {@code quoteId}, per the OpenAPI contract) would replace this without
 * changing the {@link PriceCatalog} contract.
 */
public interface PriceCatalog {

    /**
     * Resolve the current unit price for a SKU in the given currency.
     *
     * @return the unit price, or empty if the SKU is unknown or not sold in that currency
     */
    Optional<Money> unitPriceFor(String sku, String currency);
}
