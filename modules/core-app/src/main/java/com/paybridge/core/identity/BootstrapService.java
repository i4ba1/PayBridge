package com.paybridge.core.identity;

import com.paybridge.application.port.AuditStore;
import com.paybridge.application.port.AuditStore.ActorType;
import com.paybridge.application.port.AuditStore.Entry;
import com.paybridge.application.port.AuditStore.Result;
import com.paybridge.application.port.BootstrapStore;
import com.paybridge.application.port.BootstrapStore.State;
import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.application.port.IdentityStore.NewUser;
import com.paybridge.core.identity.IdentityException.Code;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * First run administrator setup (FR-001, DMR section 3). One transaction locks the singleton row, checks the token,
 * creates the administrator, consumes the state and writes the audit row. Setup succeeds once.
 */
@Service
public class BootstrapService {
    private final BootstrapStore bootstrap;
    private final IdentityStore users;
    private final AuditStore audit;
    private final PasswordEncoder encoder;
    private final TransactionTemplate tx;

    public BootstrapService(BootstrapStore bootstrap, IdentityStore users, AuditStore audit,
                            PasswordEncoder encoder, TransactionTemplate tx) {
        this.bootstrap = bootstrap;
        this.users = users;
        this.audit = audit;
        this.encoder = encoder;
        this.tx = tx;
    }

    public boolean setupRequired() {
        return !users.adminExists();
    }

    /**
     * @throws IdentityException BOOTSTRAP_TOKEN_INVALID for a wrong, expired or used token (identical for all three),
     *         SETUP_ALREADY_COMPLETED once an administrator exists, VALIDATION_ERROR for a bad username or password
     *         (only reported to a caller who holds a valid token)
     */
    public UserView setup(String token, String username, String displayName, String password, String correlationId) {
        String name = IdentityService.normalize(username);
        // Hash before the transaction: bcrypt is slow and must not hold the row lock.
        String hash = password == null ? null : encoder.encode(password);
        Outcome outcome = tx.execute(s -> attempt(token, name, displayName, password, hash, correlationId));
        if (outcome.failure != null) {
            throw outcome.failure;
        }
        return outcome.view;
    }

    private record Outcome(UserView view, IdentityException failure) {}

    private Outcome attempt(String token, String name, String displayName, String password, String hash, String correlationId) {
        var row = bootstrap.lock().orElse(null);
        if (row == null) {
            return fail(Code.BOOTSTRAP_TOKEN_INVALID, "The setup secret is not valid.");
        }
        if (row.state() == State.CONSUMED || users.adminExists()) {
            return fail(Code.SETUP_ALREADY_COMPLETED, "Setup has already been completed.");
        }
        boolean tokenOk = row.state() == State.PENDING && row.tokenHash() != null && token != null
                && MessageDigest.isEqual(sha256Hex(token).getBytes(StandardCharsets.US_ASCII),
                        row.tokenHash().getBytes(StandardCharsets.US_ASCII));
        if (!tokenOk) {
            return fail(Code.BOOTSTRAP_TOKEN_INVALID, "The setup secret is not valid.");
        }
        if (row.expired()) {
            bootstrap.markExpired();      // committed: this is a normal return, not an exception
            return fail(Code.BOOTSTRAP_TOKEN_INVALID, "The setup secret is not valid.");
        }
        var problems = new java.util.ArrayList<PasswordPolicy.Problem>(PasswordPolicy.validateUsername(name));
        problems.addAll(PasswordPolicy.validate(name, password));
        if (!problems.isEmpty()) {
            return new Outcome(null, new IdentityException(Code.VALIDATION_ERROR, "The account is not valid.", problems, null));
        }
        String id = UUID.randomUUID().toString();
        users.create(new NewUser(id, name, displayName == null || displayName.isBlank() ? name : displayName.strip(), hash,
                GlobalRole.ADMIN, false));
        if (!bootstrap.consume(id)) {
            throw new IllegalStateException("bootstrap state changed during setup");   // rolls everything back
        }
        audit.record(Entry.of(ActorType.SERVICE, null, null, "ADMIN_BOOTSTRAPPED", "user", id, Result.SUCCESS, correlationId));
        return new Outcome(new UserView(users.findById(id).orElseThrow(), List.of()), null);
    }

    private static Outcome fail(Code code, String message) {
        return new Outcome(null, new IdentityException(code, message));
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
