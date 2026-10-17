package com.paybridge.core.identity;

import com.paybridge.application.port.AuditStore;
import com.paybridge.application.port.AuditStore.ActorType;
import com.paybridge.application.port.AuditStore.Entry;
import com.paybridge.application.port.AuditStore.Result;
import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.application.port.IdentityStore.NewUser;
import com.paybridge.application.port.IdentityStore.UserChange;
import com.paybridge.application.port.IdentityStore.UserFilter;
import com.paybridge.application.port.IdentityStore.UserRecord;
import com.paybridge.application.port.LoginThrottle;
import com.paybridge.application.port.MembershipStore;
import com.paybridge.application.port.MembershipStore.Grant;
import com.paybridge.core.identity.IdentityException.Code;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Accounts, authentication and administration (FR-001 to FR-005, FR-063). Every method that changes state writes its
 * audit row in the same local transaction. Roles and memberships are always read from MySQL; nothing is cached.
 * Passwords and temporary credentials are never logged, audited or placed in exception messages.
 */
@Service
public class IdentityService {
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    static final int TEMPORARY_CREDENTIAL_LENGTH = 20;

    private final IdentityStore users;
    private final MembershipStore memberships;
    private final AuditStore audit;
    private final LoginThrottle throttle;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    /** A real hash to verify against when the account does not exist, so unknown users cost the same as wrong passwords. */
    private final String dummyHash;

    public IdentityService(IdentityStore users, MembershipStore memberships, AuditStore audit, LoginThrottle throttle,
                           PasswordEncoder encoder, TransactionTemplate tx) {
        this.users = users;
        this.memberships = memberships;
        this.audit = audit;
        this.throttle = throttle;
        this.encoder = encoder;
        this.tx = tx;
        this.dummyHash = encoder.encode("not-a-real-password-" + UUID.randomUUID());
    }

    public record Created(UserView user, String temporaryCredential) {}

    public record Reset(String userId, String temporaryCredential) {}

    // ---- authentication ----------------------------------------------------------------------------------------

    /** Wrong password, unknown user and disabled user are indistinguishable to the caller. */
    public UserView authenticate(String username, String password, String clientAddress, String correlationId) {
        String name = normalize(username);
        try {
            throttle.check(name, clientAddress);
        } catch (LoginThrottle.Throttled t) {
            throw new IdentityException(Code.RATE_LIMITED, "Too many failed attempts.", List.of(), t.retryAfterSeconds());
        } catch (LoginThrottle.Unavailable u) {
            throw new IdentityException(Code.TEMPORARILY_UNAVAILABLE, "Sign in is temporarily unavailable.");
        }
        UserRecord user = users.findByUsername(name).orElse(null);
        boolean ok = encoder.matches(password == null ? "" : password, user == null ? dummyHash : user.passwordHash())
                && user != null && user.enabled();
        if (!ok) {
            failed(name, clientAddress, correlationId);
            throw new IdentityException(Code.UNAUTHENTICATED, "The username or password is not correct.");
        }
        try {
            throttle.reset(name, clientAddress);
        } catch (LoginThrottle.Unavailable u) {
            throw new IdentityException(Code.TEMPORARILY_UNAVAILABLE, "Sign in is temporarily unavailable.");
        }
        return view(user);
    }

    private void failed(String name, String clientAddress, String correlationId) {
        try {
            if (throttle.recordFailure(name, clientAddress)) {
                // Once per window and key, as the audit vocabulary requires, to avoid log flooding.
                tx.executeWithoutResult(s -> audit.record(Entry.of(ActorType.SERVICE, null, null, "LOGIN_FAILED",
                        "user", name, Result.REJECTED, correlationId)));
            }
        } catch (LoginThrottle.Unavailable u) {
            throw new IdentityException(Code.TEMPORARILY_UNAVAILABLE, "Sign in is temporarily unavailable.");
        }
    }

    public void changePassword(String userId, String current, String next, String clientAddress, String correlationId) {
        UserRecord user = users.findById(userId).filter(UserRecord::enabled)
                .orElseThrow(() -> new IdentityException(Code.UNAUTHENTICATED, "Not signed in."));
        try {
            throttle.check(user.username(), clientAddress);
        } catch (LoginThrottle.Throttled t) {
            throw new IdentityException(Code.RATE_LIMITED, "Too many failed attempts.", List.of(), t.retryAfterSeconds());
        } catch (LoginThrottle.Unavailable u) {
            throw new IdentityException(Code.TEMPORARILY_UNAVAILABLE, "Password change is temporarily unavailable.");
        }
        if (current == null || !encoder.matches(current, user.passwordHash())) {
            failed(user.username(), clientAddress, correlationId);
            throw new IdentityException(Code.UNAUTHENTICATED, "The current password is not correct.");
        }
        var problems = new ArrayList<>(PasswordPolicy.validate(user.username(), next));
        if (next != null && next.equals(current)) {
            problems.add(new PasswordPolicy.Problem("newPassword", "UNCHANGED", "The new password must differ from the current one."));
        }
        if (!problems.isEmpty()) {
            throw new IdentityException(Code.VALIDATION_ERROR, "The new password is not acceptable.", relabel(problems), null);
        }
        String hash = encoder.encode(next);
        tx.executeWithoutResult(s -> {
            users.update(userId, new UserChange(null, null, null, hash, false));
            audit.record(Entry.of(ActorType.USER, userId, null, "PASSWORD_CHANGED", "user", userId, Result.SUCCESS, correlationId));
        });
        try {
            throttle.reset(user.username(), clientAddress);
        } catch (LoginThrottle.Unavailable u) {
            // The password is already changed; a stale counter only means the user may be throttled a little longer.
        }
    }

    private static List<PasswordPolicy.Problem> relabel(List<PasswordPolicy.Problem> in) {
        return in.stream().map(p -> new PasswordPolicy.Problem("newPassword", p.code(), p.message())).toList();
    }

    // ---- reads -------------------------------------------------------------------------------------------------

    public UserView me(String userId) {
        return users.findById(userId).map(this::view)
                .orElseThrow(() -> new IdentityException(Code.UNAUTHENTICATED, "Not signed in."));
    }

    public UserView get(String id) {
        return users.findById(id).map(this::view).orElse(null);
    }

    public List<UserView> list(Boolean enabled, String merchantId, String query, int limit, String afterUsername) {
        return users.list(new UserFilter(enabled, merchantId, query), limit, afterUsername).stream().map(this::view).toList();
    }

    // ---- administration (callers have already established the ADMIN requirement) ------------------------------

    public Created createUser(ActorContext actor, String username, String displayName, boolean admin,
                              List<Grant> grants) {
        String name = normalize(username);
        var problems = PasswordPolicy.validateUsername(name);
        if (!problems.isEmpty()) {
            throw new IdentityException(Code.VALIDATION_ERROR, "The account is not valid.", problems, null);
        }
        if (admin && !grants.isEmpty()) {
            throw new IdentityException(Code.VALIDATION_ERROR, "An administrator has no merchant memberships.");
        }
        checkGrants(grants);
        String credential = temporaryCredential();
        String id = UUID.randomUUID().toString();
        String hash = encoder.encode(credential);
        return tx.execute(s -> {
            if (users.findByUsername(name).isPresent()) {
                throw new IdentityException(Code.USERNAME_TAKEN, "That username is already in use.");
            }
            try {
                users.create(new NewUser(id, name, displayName == null || displayName.isBlank() ? name : displayName.strip(),
                        hash, admin ? GlobalRole.ADMIN : GlobalRole.USER, true));
            } catch (IdentityStore.DuplicateAccountException e) {
                throw new IdentityException(Code.USERNAME_TAKEN, "That username is already in use.");
            }
            audit.record(Entry.of(ActorType.USER, actor.userId(), null, "USER_CREATED", "user", id, Result.SUCCESS,
                    actor.correlationId()).withDetails(Map.of("admin", admin)));
            applyGrants(actor, id, grants);
            return new Created(view(users.findById(id).orElseThrow()), credential);
        });
    }

    public UserView updateUser(ActorContext actor, String id, String displayName, Boolean enabled, Boolean admin) {
        return tx.execute(s -> {
            UserRecord existing = users.findById(id).orElseThrow(() -> new IdentityException(Code.NOT_FOUND, "No such user."));
            if (id.equals(actor.userId()) && (Boolean.FALSE.equals(enabled) || Boolean.FALSE.equals(admin))) {
                throw new IdentityException(Code.INVALID_STATE, "Administrators cannot disable or demote themselves.");
            }
            var changed = new LinkedHashMap<String, Object>();
            if (displayName != null && !displayName.equals(existing.displayName())) {
                changed.put("displayName", true);
            }
            if (enabled != null && enabled != existing.enabled()) {
                changed.put("enabled", enabled);
            }
            GlobalRole role = admin == null ? null : admin ? GlobalRole.ADMIN : GlobalRole.USER;
            if (role != null && role != existing.globalRole()) {
                if (role == GlobalRole.ADMIN && !memberships.membershipsOf(id).isEmpty()) {
                    throw new IdentityException(Code.INVALID_STATE, "Remove the merchant memberships before making an administrator.");
                }
                changed.put("admin", role == GlobalRole.ADMIN);
            }
            if (changed.isEmpty()) {
                return view(existing);
            }
            users.update(id, new UserChange(displayName, enabled, role, null, null));
            audit.record(Entry.of(ActorType.USER, actor.userId(), null, "USER_UPDATED", "user", id, Result.SUCCESS,
                    actor.correlationId()).withDetails(changed));
            return view(users.findById(id).orElseThrow());
        });
    }

    public Reset resetPassword(ActorContext actor, String id) {
        String credential = temporaryCredential();
        String hash = encoder.encode(credential);
        return tx.execute(s -> {
            users.findById(id).orElseThrow(() -> new IdentityException(Code.NOT_FOUND, "No such user."));
            users.update(id, new UserChange(null, null, null, hash, true));
            audit.record(Entry.of(ActorType.USER, actor.userId(), null, "USER_PASSWORD_RESET", "user", id, Result.SUCCESS,
                    actor.correlationId()));
            return new Reset(id, credential);
        });
    }

    public UserView setMemberships(ActorContext actor, String id, List<Grant> grants) {
        checkGrants(grants);
        return tx.execute(s -> {
            UserRecord existing = users.findById(id).orElseThrow(() -> new IdentityException(Code.NOT_FOUND, "No such user."));
            if (existing.globalRole() == GlobalRole.ADMIN && !grants.isEmpty()) {
                throw new IdentityException(Code.INVALID_STATE, "An administrator has no merchant memberships.");
            }
            applyGrants(actor, id, grants);
            return view(existing);
        });
    }

    private void applyGrants(ActorContext actor, String userId, List<Grant> grants) {
        for (var change : memberships.replace(userId, grants)) {
            var details = new LinkedHashMap<String, Object>();
            details.put("merchantId", change.merchantId());
            details.put("oldRole", change.oldRole() == null ? "NONE" : change.oldRole().name());
            details.put("newRole", change.newRole() == null ? "NONE" : change.newRole().name());
            audit.record(Entry.of(ActorType.USER, actor.userId(), change.merchantId(), "MEMBERSHIP_CHANGED", "membership",
                    change.membershipId(), Result.SUCCESS, actor.correlationId()).withDetails(details));
        }
    }

    private void checkGrants(List<Grant> grants) {
        var seen = new HashSet<String>();
        for (Grant g : grants) {
            if (!seen.add(g.merchantId())) {
                throw new IdentityException(Code.VALIDATION_ERROR, "A merchant may appear only once.");
            }
            if (memberships.findMerchant(g.merchantId()).isEmpty()) {
                throw new IdentityException(Code.NOT_FOUND, "No such merchant.");
            }
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private UserView view(UserRecord user) {
        return new UserView(user, memberships.membershipsOf(user.id()));
    }

    static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(java.util.Locale.ROOT);
    }

    private String temporaryCredential() {
        var sb = new StringBuilder(TEMPORARY_CREDENTIAL_LENGTH);
        for (int i = 0; i < TEMPORARY_CREDENTIAL_LENGTH; i++) {
            sb.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }
}
