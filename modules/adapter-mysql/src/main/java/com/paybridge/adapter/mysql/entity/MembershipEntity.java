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
@Table(name = "membership")
public class MembershipEntity {
    public enum Role { OPERATOR, VIEWER }

    @JdbcTypeCode(SqlTypes.CHAR) @Id @Column(length = 36) private String id;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "user_id", nullable = false, length = 36) private String userId;
    @JdbcTypeCode(SqlTypes.CHAR) @Column(name = "merchant_id", nullable = false, length = 36) private String merchantId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Role role;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected MembershipEntity() {}

    public MembershipEntity(String id, String userId, String merchantId, Role role, Instant now) {
        this.id = id;
        this.userId = userId;
        this.merchantId = merchantId;
        this.role = role;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void changeRole(Role role, Instant now) {
        this.role = role;
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getUserId() { return userId; }
    public String getMerchantId() { return merchantId; }
    public Role getRole() { return role; }
}
