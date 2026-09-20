package com.sajidriaz.orderplatform.orderservice.pricing;

import com.sajidriaz.orderplatform.common.money.Money;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for server-side price resolution and Money arithmetic (no client-supplied
 * prices are ever trusted — REST-API-GUIDE §1 "Pricing").
 */
class InMemoryPriceCatalogTest
{

    private final PriceCatalog catalog = new InMemoryPriceCatalog();

    @Test
    void resolvesKnownSkuInUsd()
    {
        Optional<Money> price = catalog.unitPriceFor("SKU-1001", "USD");
        assertThat(price).contains(new Money(1999, "USD"));
    }

    @Test
    void unknownSkuIsEmpty()
    {
        assertThat(catalog.unitPriceFor("SKU-DOES-NOT-EXIST", "USD")).isEmpty();
    }

    @Test
    void unsupportedCurrencyIsEmpty()
    {
        assertThat(catalog.unitPriceFor("SKU-1001", "EUR")).isEmpty();
    }

    @Test
    void lineTotalIsUnitPriceTimesQuantity()
    {
        Money unitPrice = catalog.unitPriceFor("SKU-1001", "USD").orElseThrow();
        Money lineTotal = unitPrice.times(3);
        assertThat(lineTotal.minorUnits()).isEqualTo(1999L * 3);
        assertThat(lineTotal.currency()).isEqualTo("USD");
    }

    @Test
    void totalIsSumOfLineTotalsWithinSameCurrency()
    {
        Money line1 = catalog.unitPriceFor("SKU-1001", "USD").orElseThrow().times(2);
        Money line2 = catalog.unitPriceFor("SKU-1002", "USD").orElseThrow().times(1);
        Money total = line1.plus(line2);
        assertThat(total.minorUnits()).isEqualTo(1999L * 2 + 2999L);
    }

    @Test
    void moneyRejectsCrossCurrencyArithmetic()
    {
        Money usd = new Money(100, "USD");
        Money eur = new Money(100, "EUR");
        assertThat(org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> usd.plus(eur)))
                .hasMessageContaining("currency mismatch");
    }
}
