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

@Entity
@Table(name = "merchant")
public class MerchantEntity {
    public enum Status { ACTIVE, SUSPENDED }

    @JdbcTypeCode(SqlTypes.CHAR) @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 32) private String code;
    @Column(nullable = false, length = 120) private String name;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(nullable = false, length = 3, columnDefinition = "char(3)") private String currency;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected MerchantEntity() {}

    public String getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCurrency() { return currency; }
    public Status getStatus() { return status; }
    public long getVersion() { return version; }
}
