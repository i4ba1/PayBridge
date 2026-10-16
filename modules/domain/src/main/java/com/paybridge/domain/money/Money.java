package com.paybridge.domain.money;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.regex.Pattern;

/** Positive amount in minor units. All arithmetic is checked; overflow throws {@link ArithmeticException}. */
public record Money(long amountMinor, CurrencyCode currency) {

    private static final Pattern PLAIN_DECIMAL = Pattern.compile("\\d+(\\.\\d+)?");

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive: " + amountMinor);
        }
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(amountMinor, other.amountMinor), currency);
    }

    /** Decimal text for API use, for example 2500 USD becomes "25.00" and 150000 IDR becomes "150000". */
    public String toDecimalString() {
        return BigDecimal.valueOf(amountMinor, currency.scale()).toPlainString();
    }

    /** Minor units as a string; API numbers are strings so they never lose precision. */
    public String toMinorString() {
        return Long.toString(amountMinor);
    }

    public static Money ofMinorString(String minor, CurrencyCode currency) {
        Objects.requireNonNull(minor, "minor");
        if (!minor.matches("\\d{1,18}")) {
            throw new IllegalArgumentException("amountMinor must be a plain positive integer: " + minor);
        }
        return new Money(Long.parseLong(minor), currency);
    }

    /** Parses "25.00" or "150000"; more fraction digits than the currency scale are rejected, never rounded. */
    public static Money ofDecimalString(String text, CurrencyCode currency) {
        Objects.requireNonNull(text, "text");
        Objects.requireNonNull(currency, "currency");
        if (!PLAIN_DECIMAL.matcher(text).matches()) {
            throw new IllegalArgumentException("Not a plain non-negative decimal: " + text);
        }
        BigDecimal value = new BigDecimal(text);
        if (value.scale() > currency.scale()) {
            throw new IllegalArgumentException(
                    "Too many fraction digits for " + currency + " (scale " + currency.scale() + "): " + text);
        }
        return new Money(value.setScale(currency.scale()).unscaledValue().longValueExact(), currency);
    }

    private void requireSameCurrency(Money other) {
        if (currency != other.currency) {
            throw new IllegalArgumentException("Currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
