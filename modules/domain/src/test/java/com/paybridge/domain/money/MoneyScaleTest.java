package com.paybridge.domain.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyScaleTest {

    @Test
    void idrHasScaleZero() {
        Money m = Money.ofDecimalString("150000", CurrencyCode.IDR);
        assertThat(m.amountMinor()).isEqualTo(150_000L);
        assertThat(m.toDecimalString()).isEqualTo("150000");
    }

    @Test
    void usdHasScaleTwo() {
        Money m = Money.ofDecimalString("25.00", CurrencyCode.USD);
        assertThat(m.amountMinor()).isEqualTo(2500L);
        assertThat(m.toDecimalString()).isEqualTo("25.00");
        assertThat(Money.ofDecimalString("25", CurrencyCode.USD).amountMinor()).isEqualTo(2500L);
        assertThat(Money.ofDecimalString("0.05", CurrencyCode.USD).toDecimalString()).isEqualTo("0.05");
    }

    @Test
    void roundTripsMinorStrings() {
        assertThat(Money.ofMinorString("2500", CurrencyCode.USD).toMinorString()).isEqualTo("2500");
    }

    @ParameterizedTest
    @ValueSource(strings = {"150000.00", "1.5"})
    void idrRejectsFractionDigits(String text) {
        assertThatThrownBy(() -> Money.ofDecimalString(text, CurrencyCode.IDR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void usdRejectsMoreThanTwoFractionDigitsInsteadOfRounding() {
        assertThatThrownBy(() -> Money.ofDecimalString("25.001", CurrencyCode.USD))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"-5", "1e3", "abc", "", " 5", "5 ", "+5"})
    void rejectsMalformedAndSignedText(String text) {
        assertThatThrownBy(() -> Money.ofDecimalString(text, CurrencyCode.IDR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsZeroAndNegativeAmounts() {
        assertThatThrownBy(() -> new Money(0, CurrencyCode.IDR)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Money(-1, CurrencyCode.USD)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.ofDecimalString("0", CurrencyCode.IDR))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Money.ofMinorString("0", CurrencyCode.IDR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void additionOverflowIsRejected() {
        Money big = new Money(Long.MAX_VALUE, CurrencyCode.IDR);
        assertThatThrownBy(() -> big.plus(new Money(1, CurrencyCode.IDR))).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void decimalOverflowIsRejected() {
        assertThatThrownBy(() -> Money.ofDecimalString("92233720368547758.08", CurrencyCode.USD))
                .isInstanceOf(ArithmeticException.class);
        assertThatThrownBy(() -> Money.ofMinorString("9223372036854775808", CurrencyCode.IDR))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void additionKeepsCurrencyAndRejectsMismatch() {
        assertThat(new Money(100, CurrencyCode.USD).plus(new Money(250, CurrencyCode.USD)))
                .isEqualTo(new Money(350, CurrencyCode.USD));
        assertThatThrownBy(() -> new Money(1, CurrencyCode.USD).plus(new Money(1, CurrencyCode.IDR)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedCurrencyIsRejected() {
        assertThat(CurrencyCode.parse("IDR")).isEqualTo(CurrencyCode.IDR);
        assertThatThrownBy(() -> CurrencyCode.parse("EUR")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CurrencyCode.parse("idr")).isInstanceOf(IllegalArgumentException.class);
    }
}
