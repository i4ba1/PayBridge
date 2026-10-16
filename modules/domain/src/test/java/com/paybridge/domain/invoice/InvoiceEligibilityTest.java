package com.paybridge.domain.invoice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.paybridge.domain.money.CurrencyCode;
import com.paybridge.domain.money.Money;
import com.paybridge.domain.payment.PaymentStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InvoiceEligibilityTest {

    private static Invoice invoice(String reference) {
        return new Invoice(UUID.randomUUID(), UUID.randomUUID(), reference, new Money(150_000, CurrencyCode.IDR), null);
    }

    @Test
    void eligibilityFollowsActivePaymentStatus() {
        Invoice i = invoice("INV-0001");
        assertThat(i.ineligibility(null)).isEmpty();
        assertThat(i.ineligibility(PaymentStatus.FAILED)).isEmpty();
        assertThat(i.ineligibility(PaymentStatus.REQUESTED)).isEqualTo(Optional.of(IneligibleReason.ACTIVE_PAYMENT));
        assertThat(i.ineligibility(PaymentStatus.PROCESSING)).isEqualTo(Optional.of(IneligibleReason.ACTIVE_PAYMENT));
        assertThat(i.ineligibility(PaymentStatus.UNKNOWN)).isEqualTo(Optional.of(IneligibleReason.UNCERTAIN_PAYMENT));
        assertThat(i.ineligibility(PaymentStatus.SUCCEEDED)).isEqualTo(Optional.of(IneligibleReason.ALREADY_PAID));
    }

    @Test
    void referenceMustBeNonBlankAndAtMost40() {
        assertThat(invoice("x".repeat(40)).reference()).hasSize(40);
        assertThatThrownBy(() -> invoice("x".repeat(41))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> invoice(" ")).isInstanceOf(IllegalArgumentException.class);
    }
}
