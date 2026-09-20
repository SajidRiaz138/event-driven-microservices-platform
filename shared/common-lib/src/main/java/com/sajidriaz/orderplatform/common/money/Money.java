package com.sajidriaz.orderplatform.common.money;

import java.util.Currency;
import java.util.Objects;

/**
 * Money as integer minor units + ISO-4217 currency — never a floating-point type
 * (NFR §7, ADR-0016). {@code 12.34 USD} is {@code minorUnits=1234, currency="USD"}.
 *
 * <p>This is the domain value type; the Avro {@code Money} record is the wire form.
 * Arithmetic is only permitted within a single currency.
 */
public record Money(long minorUnits, String currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        // Validate ISO-4217; throws IllegalArgumentException for unknown codes.
        Currency.getInstance(currency);
        if (minorUnits < 0) {
            throw new IllegalArgumentException("minorUnits must be >= 0: " + minorUnits);
        }
    }

    public static Money of(long minorUnits, String currency) {
        return new Money(minorUnits, currency);
    }

    /** Add two amounts of the same currency. */
    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(this.minorUnits, other.minorUnits), currency);
    }

    /** Multiply by a whole quantity (e.g. unit price × quantity). */
    public Money times(long quantity) {
        if (quantity < 0) {
            throw new IllegalArgumentException("quantity must be >= 0: " + quantity);
        }
        return new Money(Math.multiplyExact(this.minorUnits, quantity), currency);
    }

    private void requireSameCurrency(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: " + this.currency + " vs " + other.currency);
        }
    }
}
