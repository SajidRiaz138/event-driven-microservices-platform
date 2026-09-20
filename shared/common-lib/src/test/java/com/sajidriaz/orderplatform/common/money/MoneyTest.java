package com.sajidriaz.orderplatform.common.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MoneyTest
{

    @Test
    void times_multipliesUnitPriceByQuantity()
    {
        Money unit = Money.of(1999, "USD");
        assertEquals(Money.of(3998, "USD"), unit.times(2));
    }

    @Test
    void plus_addsSameCurrency()
    {
        assertEquals(Money.of(3000, "USD"), Money.of(1000, "USD").plus(Money.of(2000, "USD")));
    }

    @Test
    void plus_rejectsCurrencyMismatch()
    {
        assertThrows(IllegalArgumentException.class,
                () -> Money.of(1000, "USD").plus(Money.of(1000, "EUR")));
    }

    @Test
    void constructor_rejectsUnknownCurrency()
    {
        assertThrows(IllegalArgumentException.class, () -> Money.of(100, "XYZ"));
    }

    @Test
    void constructor_rejectsNegativeAmount()
    {
        assertThrows(IllegalArgumentException.class, () -> Money.of(-1, "USD"));
    }
}
