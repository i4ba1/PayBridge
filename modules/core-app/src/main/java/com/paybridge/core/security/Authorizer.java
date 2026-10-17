package com.paybridge.core.security;

import com.paybridge.application.port.AuditStore;
import com.paybridge.application.port.AuditStore.ActorType;
import com.paybridge.application.port.AuditStore.Entry;
import com.paybridge.application.port.AuditStore.Result;
import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.application.port.IdentityStore.UserRecord;
import com.paybridge.application.port.MembershipStore;
import com.paybridge.application.port.MembershipStore.Role;
import com.paybridge.core.identity.ActorContext;
import com.paybridge.core.identity.IdentityException.Code;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Decides, for one request, whether the verified service context satisfies a field's @auth requirement
 * (API contract section 5.3). The account, the administrator attribute and the membership are read from MySQL on every
 * call, so disabling a user or changing a role applies to the next protected operation (FR-003, FR-005). Nothing is
 * cached across requests, and nothing is read from the token except the ids.
 */
@Component
public class Authorizer {
    public enum Requires { SERVICE_ANONYMOUS, USER, VIEWER, OPERATOR, ADMIN }

    public enum Scope { NONE, MERCHANT, GLOBAL }

    /** The three operations a user who must change the temporary credential may still call. */
    static final Set<String> PASSWORD_CHANGE_ALLOWED = Set.of("me", "changePassword", "myMerchants");

    public record Requirement(String field, Requires requires, Scope scope) {}

    public sealed interface Decision permits Granted, Denied {}

    public record Granted(ActorContext actor, UserRecord user) implements Decision {}

    public record Denied(Code code) implements Decision {}

    private final IdentityStore users;
    private final MembershipStore memberships;
    private final AuditStore audit;
    private final TransactionTemplate tx;

    public Authorizer(IdentityStore users, MembershipStore memberships, AuditStore audit, TransactionTemplate tx) {
        this.users = users;
        this.memberships = memberships;
        this.audit = audit;
        this.tx = tx;
    }

    public Decision authorize(ServiceContextVerifier.Claims claims, String correlationId, Requirement req) {
        if (req.requires() == Requires.SERVICE_ANONYMOUS) {
            return new Granted(new ActorContext(claims.userId(), null, correlationId), null);
        }
        if (claims.userId() == null) {
            return new Denied(Code.UNAUTHENTICATED);
        }
        UserRecord user = users.findById(claims.userId()).orElse(null);
        if (user == null || !user.enabled()) {
            return new Denied(Code.UNAUTHENTICATED);
        }
        if (user.mustChangePassword() && !PASSWORD_CHANGE_ALLOWED.contains(req.field())) {
            return new Denied(Code.PASSWORD_CHANGE_REQUIRED);
        }
        boolean admin = user.globalRole() == GlobalRole.ADMIN;
        return switch (req.requires()) {
            case USER -> new Granted(new ActorContext(user.id(), null, correlationId), user);
            case ADMIN -> {
                if (!admin) {
                    yield new Denied(Code.FORBIDDEN);
                }
                yield req.scope() == Scope.MERCHANT
                        ? merchantAdmin(user, claims.merchantId(), correlationId)
                        : new Granted(new ActorContext(user.id(), null, correlationId), user);
            }
            case VIEWER, OPERATOR -> merchantScoped(user, admin, claims.merchantId(), req.requires(), correlationId);
            case SERVICE_ANONYMOUS -> throw new IllegalStateException("handled above");
        };
    }

    private Decision merchantScoped(UserRecord user, boolean admin, String merchantId, Requires needed, String correlationId) {
        if (merchantId == null || memberships.findMerchant(merchantId).isEmpty()) {
            return new Denied(Code.FORBIDDEN);
        }
        Role role = memberships.roleOf(user.id(), merchantId).orElse(null);
        if (role != null) {
            boolean enough = role == Role.OPERATOR || needed == Requires.VIEWER;
            return enough ? new Granted(new ActorContext(user.id(), merchantId, correlationId), user) : new Denied(Code.FORBIDDEN);
        }
        // No membership: an administrator may read an explicitly selected merchant, and the access is audited.
        if (admin && needed == Requires.VIEWER) {
            return crossMerchant(user, merchantId, correlationId);
        }
        return new Denied(Code.FORBIDDEN);
    }

    private Decision merchantAdmin(UserRecord user, String merchantId, String correlationId) {
        if (merchantId == null || memberships.findMerchant(merchantId).isEmpty()) {
            return new Denied(Code.FORBIDDEN);
        }
        return crossMerchant(user, merchantId, correlationId);
    }

    private Decision crossMerchant(UserRecord user, String merchantId, String correlationId) {
        try {
            tx.executeWithoutResult(s -> audit.record(Entry.of(ActorType.USER, user.id(), merchantId,
                    "ADMIN_CROSS_MERCHANT_ACCESS", "merchant", merchantId, Result.SUCCESS, correlationId)
                    .withDetails(Map.of())));
        } catch (RuntimeException e) {
            // Access that cannot be audited is not granted.
            return new Denied(Code.FORBIDDEN);
        }
        return new Granted(new ActorContext(user.id(), merchantId, correlationId), user);
    }
}
