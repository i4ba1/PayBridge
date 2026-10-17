package com.paybridge.core.graphql.model;

import java.time.Instant;
import java.util.List;

/**
 * Java shapes of the identity part of the schema. DGS resolves union members by simple class name, so each record is
 * named exactly like its GraphQL type.
 */
public final class IdentityTypes {
    private IdentityTypes() {}

    public record Membership(String merchantId, String merchantName, String role) {}

    public record Merchant(String id, String name, String currency, String role) {}

    public record User(String id, String username, String displayName, boolean admin, boolean enabled,
                       boolean passwordChangeRequired, Instant createdAt, List<Membership> memberships) {}

    public record FieldProblem(String field, String code, String message) {}

    public record OperationProblem(String code, String message, Integer retryAfterSeconds,
                                   List<FieldProblem> fieldProblems, String existingPaymentId) {
        public static OperationProblem of(String code, String message) {
            return new OperationProblem(code, message, null, null, null);
        }
    }

    public record SetupStatus(boolean setupRequired) {}

    public record Authenticated(User user) {}

    public record AdministratorCreated(User user) {}

    public record PasswordChanged(boolean changed) {}

    public record UserCreated(User user, String temporaryCredential) {}

    public record UserUpdated(User user) {}

    public record PasswordReset(String userId, String temporaryCredential, boolean passwordChangeRequired) {}

    public record MembershipsSet(String userId, List<Membership> memberships) {}

    public record Page(String nextCursor, int limit) {}

    public record UserList(List<User> items, Page page) {}

    // inputs
    public record AuthenticateInput(String username, String password) {
        @Override
        public String toString() {
            return "AuthenticateInput[username=" + username + "]";
        }
    }

    public record BootstrapInput(String bootstrapToken, String username, String displayName, String password) {
        @Override
        public String toString() {
            return "BootstrapInput[username=" + username + "]";
        }
    }

    public record ChangePasswordInput(String currentPassword, String newPassword) {
        @Override
        public String toString() {
            return "ChangePasswordInput[]";
        }
    }

    public record MembershipInput(String merchantId, String role) {}

    public record CreateUserInput(String username, String displayName, Boolean admin, List<MembershipInput> memberships) {}

    public record UpdateUserInput(String displayName, Boolean enabled, Boolean admin) {}
}
