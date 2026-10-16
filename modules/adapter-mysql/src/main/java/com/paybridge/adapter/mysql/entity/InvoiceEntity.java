package com.paybridge.adapter.mysql.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "invoice")
public class InvoiceEntity {
    private static final Set<String> CURRENCIES = Set.of("IDR", "USD");

    @JdbcTypeCode(SqlTypes.CHAR) @Id @Column(length = 36) private String id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "merchant_id", nullable = false, length = 36) private String merchantId;
    @Column(nullable = false, length = 40) private String reference;
    @Column(nullable = false, length = 200) private String description;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(nullable = false, length = 3, columnDefinition = "char(3)") private String currency;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "active_payment_id", length = 36) private String activePaymentId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Version private long version;

    protected InvoiceEntity() {}

    /** The mapping rejects what the database also rejects, so a bad row fails before the round trip. */
    public InvoiceEntity(String id, String merchantId, String reference, String description, long amountMinor,
                         String currency, Instant createdAt) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("invoice amount must be positive");
        }
        if (!CURRENCIES.contains(currency)) {
            throw new IllegalArgumentException("unsupported currency");
        }
        this.id = id;
        this.merchantId = merchantId;
        this.reference = reference;
        this.description = description;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getReference() { return reference; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getActivePaymentId() { return activePaymentId; }
    public long getVersion() { return version; }
}
