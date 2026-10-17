package com.paybridge.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Persistence port for accounts. Implementations return detached records; entities never cross this boundary. */
public interface IdentityStore {

    Optional<UserRecord> findByUsername(String username);

    Optional<UserRecord> findById(String id);

    boolean adminExists();

    /** Applies the non null fields of the change and returns the new state. Optimistic locking guards concurrent edits. */
    UserRecord update(String id, UserChange change);

    List<UserRecord> list(UserFilter filter, int limit, String afterUsername);

    record UserChange(String displayName, Boolean enabled, GlobalRole globalRole, String passwordHash,
                      Boolean mustChangePassword) {
        @Override
        public String toString() {
            return "UserChange[displayName=" + displayName + ", enabled=" + enabled + ", globalRole=" + globalRole + "]";
        }
    }

    record UserFilter(Boolean enabled, String merchantId, String query) {}

    /** Creates the account. Fails with {@link DuplicateAccountException} when the username or id is already taken. */
    UserRecord create(NewUser user);

    enum GlobalRole { ADMIN, USER }

    final class DuplicateAccountException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public DuplicateAccountException(String username, Throwable cause) {
            super("account already exists: " + username, cause);
        }
    }

    /** The password hash is deliberately left out of toString. */
    record UserRecord(String id, String username, String displayName, String passwordHash, GlobalRole globalRole,
                      boolean enabled, boolean mustChangePassword, Instant createdAt, long version) {
        @Override
        public String toString() {
            return "UserRecord[id=" + id + ", username=" + username + ", globalRole=" + globalRole + "]";
        }
    }

    record NewUser(String id, String username, String displayName, String passwordHash, GlobalRole globalRole,
                   boolean mustChangePassword) {
        @Override
        public String toString() {
            return "NewUser[id=" + id + ", username=" + username + ", globalRole=" + globalRole + "]";
        }
    }
}
