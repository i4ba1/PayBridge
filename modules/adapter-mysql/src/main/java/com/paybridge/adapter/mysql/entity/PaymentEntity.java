package com.paybridge.adapter.mysql.entity;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

/**
 * Read mapping of payment in this task. {@code version} is the Hibernate lock counter; {@code aggregateVersion} is the
 * business counter that events and the API expose (DMR section 1). The generated column blocking_invoice_id is owned
 * by the database.
 */
@Entity
@Table(name = "payment")
public class PaymentEntity {
    public enum Status { REQUESTED, PROCESSING, SUCCEEDED, FAILED, UNKNOWN }

    @JdbcTypeCode(SqlTypes.CHAR) @Id @Column(length = 36) private String id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "merchant_id", nullable = false, length = 36) private String merchantId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "invoice_id", nullable = false, length = 36) private String invoiceId;
    @Column(name = "method_code", nullable = false, length = 32) private String methodCode;
    @Column(name = "method_token", nullable = false, length = 128) private String methodToken;
    @Column(name = "amount_minor", nullable = false) private long amountMinor;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(nullable = false, length = 3, columnDefinition = "char(3)") private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "aggregate_version", nullable = false) private long aggregateVersion;
    @Version private long version;
    @Column(name = "provider_key", nullable = false, length = 64) private String providerKey;
    @Column(name = "provider_reference", length = 64) private String providerReference;
    @Column(name = "provider_revision") private Long providerRevision;
    @Column(name = "decline_code", length = 40) private String declineCode;
    @Column(name = "scenario_run_id", nullable = false, length = 64) private String scenarioRunId;
    @Column(name = "correlation_id", nullable = false, length = 64) private String correlationId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "requested_by", nullable = false, length = 36) private String requestedBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Column(name = "completed_at") private Instant completedAt;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "blocking_invoice_id", insertable = false, updatable = false, length = 36)
    private String blockingInvoiceId;

    protected PaymentEntity() {}

    public String getId() { return id; }
    public String getMerchantId() { return merchantId; }
    public String getInvoiceId() { return invoiceId; }
    public String getMethodCode() { return methodCode; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public long getAggregateVersion() { return aggregateVersion; }
    public String getProviderKey() { return providerKey; }
    public String getProviderReference() { return providerReference; }
    public String getScenarioRunId() { return scenarioRunId; }
    public String getCorrelationId() { return correlationId; }
    public String getRequestedBy() { return requestedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getCompletedAt() { return completedAt; }
}
