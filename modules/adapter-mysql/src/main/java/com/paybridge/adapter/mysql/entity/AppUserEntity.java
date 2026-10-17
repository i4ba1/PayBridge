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
@Table(name = "app_user")
public class AppUserEntity {
    public enum Role { ADMIN, USER }

    @JdbcTypeCode(SqlTypes.CHAR) @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 64) private String username;
    @Column(name = "display_name", nullable = false, length = 120) private String displayName;
    @Column(name = "password_hash", nullable = false, length = 100) private String passwordHash;
    @Enumerated(EnumType.STRING) @Column(name = "global_role", nullable = false) private Role globalRole;
    @Column(nullable = false) private boolean enabled;
    @Column(name = "must_change_password", nullable = false) private boolean mustChangePassword;
    @Column(name = "password_changed_at") private Instant passwordChangedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected AppUserEntity() {}

    public AppUserEntity(String id, String username, String displayName, String passwordHash, Role globalRole,
                         boolean mustChangePassword, Instant now) {
        this.id = id;
        this.username = username;
        this.displayName = displayName;
        this.passwordHash = passwordHash;
        this.globalRole = globalRole;
        this.enabled = true;
        this.mustChangePassword = mustChangePassword;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Applies the non null parts of a change; Hibernate bumps the lock counter on flush. */
    public void apply(String displayName, Boolean enabled, Role role, String passwordHash, Boolean mustChange,
                      Instant now) {
        if (displayName != null) {
            this.displayName = displayName;
        }
        if (enabled != null) {
            this.enabled = enabled;
        }
        if (role != null) {
            this.globalRole = role;
        }
        if (passwordHash != null) {
            this.passwordHash = passwordHash;
            this.passwordChangedAt = now;
        }
        if (mustChange != null) {
            this.mustChangePassword = mustChange;
        }
        this.updatedAt = now;
    }

    public String getId() { return id; }
    public String getUsername() { return username; }
    public String getDisplayName() { return displayName; }
    public String getPasswordHash() { return passwordHash; }
    public Role getGlobalRole() { return globalRole; }
    public boolean isEnabled() { return enabled; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
