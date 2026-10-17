package com.paybridge.core.graphql;

import com.netflix.graphql.dgs.DgsComponent;
import com.netflix.graphql.dgs.DgsMutation;
import com.netflix.graphql.dgs.DgsQuery;
import com.netflix.graphql.dgs.InputArgument;
import com.paybridge.application.port.MembershipStore;
import com.paybridge.application.port.MembershipStore.Grant;
import com.paybridge.core.graphql.model.IdentityTypes.*;
import com.paybridge.core.identity.ActorContext;
import com.paybridge.core.identity.BootstrapService;
import com.paybridge.core.identity.IdentityException;
import com.paybridge.core.identity.IdentityService;
import com.paybridge.core.identity.UserView;
import com.paybridge.core.security.Authorizer.Granted;
import graphql.schema.DataFetchingEnvironment;
import java.util.List;
import java.util.function.Supplier;

/**
 * Identity operations of the core schema. Authorization has already happened in {@link AuthorizationInstrumentation};
 * the resolvers read the resulting {@link Granted} from the local context and never look at the token again. Domain
 * failures become typed OperationProblem results.
 */
@DgsComponent
public class IdentityDataFetcher {
    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final IdentityService identity;
    private final BootstrapService bootstrap;
    private final MembershipStore memberships;

    public IdentityDataFetcher(IdentityService identity, BootstrapService bootstrap, MembershipStore memberships) {
        this.identity = identity;
        this.bootstrap = bootstrap;
        this.memberships = memberships;
    }

    // ---- queries -----------------------------------------------------------------------------------------------

    @DgsQuery
    public SetupStatus setupStatus() {
        return new SetupStatus(bootstrap.setupRequired());
    }

    @DgsQuery
    public User me(DataFetchingEnvironment env) {
        return toUser(identity.me(granted(env).actor().userId()));
    }

    @DgsQuery
    public List<Merchant> myMerchants(DataFetchingEnvironment env) {
        UserView view = identity.me(granted(env).actor().userId());
        return view.memberships().stream()
                .map(m -> new Merchant(m.merchantId(), m.merchantName(), m.currency(), m.role().name())).toList();
    }

    @DgsQuery
    public Object users(@InputArgument Boolean enabled, @InputArgument String merchantId, @InputArgument String q,
                        @InputArgument Integer limit, @InputArgument String cursor) {
        return problems(() -> {
            int size = limit(limit);
            // Ask for one more row to know whether another page exists. The cursor is the last username.
            var rows = identity.list(enabled, merchantId, q, size + 1, cursor);
            boolean more = rows.size() > size;
            var page = more ? rows.subList(0, size) : rows;
            String next = more ? page.get(page.size() - 1).user().username() : null;
            return new UserList(page.stream().map(IdentityDataFetcher::toUser).toList(), new Page(next, size));
        });
    }

    @DgsQuery
    public User user(@InputArgument String id) {
        UserView view = identity.get(id);
        return view == null ? null : toUser(view);
    }

    // ---- mutations ---------------------------------------------------------------------------------------------

    @DgsMutation
    public Object bootstrapAdministrator(@InputArgument BootstrapInput input, DataFetchingEnvironment env) {
        return problems(() -> new AdministratorCreated(toUser(bootstrap.setup(input.bootstrapToken(), input.username(),
                input.displayName(), input.password(), granted(env).actor().correlationId()))));
    }

    @DgsMutation
    public Object authenticate(@InputArgument AuthenticateInput input, DataFetchingEnvironment env) {
        String address = env.getGraphQlContext().getOrDefault(ServiceContextInterceptor.CLIENT_ADDRESS, "unknown");
        return problems(() -> new Authenticated(toUser(identity.authenticate(input.username(), input.password(), address,
                granted(env).actor().correlationId()))));
    }

    @DgsMutation
    public Object changePassword(@InputArgument ChangePasswordInput input, DataFetchingEnvironment env) {
        String address = env.getGraphQlContext().getOrDefault(ServiceContextInterceptor.CLIENT_ADDRESS, "unknown");
        ActorContext actor = granted(env).actor();
        return problems(() -> {
            identity.changePassword(actor.userId(), input.currentPassword(), input.newPassword(), address,
                    actor.correlationId());
            return new PasswordChanged(true);
        });
    }

    @DgsMutation
    public Object createUser(@InputArgument CreateUserInput input, DataFetchingEnvironment env) {
        return problems(() -> {
            var created = identity.createUser(granted(env).actor(), input.username(), input.displayName(),
                    Boolean.TRUE.equals(input.admin()), grants(input.memberships()));
            return new UserCreated(toUser(created.user()), created.temporaryCredential());
        });
    }

    @DgsMutation
    public Object updateUser(@InputArgument String id, @InputArgument UpdateUserInput input, DataFetchingEnvironment env) {
        return problems(() -> new UserUpdated(toUser(identity.updateUser(granted(env).actor(), id, input.displayName(),
                input.enabled(), input.admin()))));
    }

    @DgsMutation
    public Object resetUserPassword(@InputArgument String id, DataFetchingEnvironment env) {
        return problems(() -> {
            var reset = identity.resetPassword(granted(env).actor(), id);
            return new PasswordReset(reset.userId(), reset.temporaryCredential(), true);
        });
    }

    @DgsMutation
    public Object setUserMemberships(@InputArgument String id, @InputArgument List<MembershipInput> memberships,
                                     DataFetchingEnvironment env) {
        return problems(() -> {
            UserView view = identity.setMemberships(granted(env).actor(), id, grants(memberships));
            UserView fresh = identity.get(view.user().id());
            return new MembershipsSet(fresh.user().id(), toUser(fresh).memberships());
        });
    }

    // ---- helpers -----------------------------------------------------------------------------------------------

    private static Granted granted(DataFetchingEnvironment env) {
        Object local = env.getLocalContext();
        if (local instanceof Granted g) {
            return g;
        }
        throw new IllegalStateException("operation reached without authorization");
    }

    private static int limit(Integer requested) {
        if (requested == null) {
            return DEFAULT_LIMIT;
        }
        if (requested < 1 || requested > MAX_LIMIT) {
            throw new IdentityException(IdentityException.Code.VALIDATION_ERROR, "limit must be between 1 and " + MAX_LIMIT);
        }
        return requested;
    }

    private static List<Grant> grants(List<MembershipInput> input) {
        if (input == null) {
            return List.of();
        }
        try {
            return input.stream().map(m -> new Grant(m.merchantId(), MembershipStore.Role.valueOf(m.role()))).toList();
        } catch (IllegalArgumentException e) {
            throw new IdentityException(IdentityException.Code.VALIDATION_ERROR, "Unknown membership role.");
        }
    }

    private static Object problems(Supplier<Object> work) {
        try {
            return work.get();
        } catch (IdentityException e) {
            var fields = e.fieldProblems().isEmpty() ? null
                    : e.fieldProblems().stream().map(p -> new FieldProblem(p.field(), p.code(), p.message())).toList();
            return new OperationProblem(e.code().name(), e.getMessage(),
                    e.retryAfterSeconds() == null ? null : Math.toIntExact(e.retryAfterSeconds()), fields, null);
        }
    }

    static User toUser(UserView v) {
        var u = v.user();
        return new User(u.id(), u.username(), u.displayName(), v.admin(), u.enabled(), u.mustChangePassword(),
                u.createdAt(), v.memberships().stream()
                .map(m -> new Membership(m.merchantId(), m.merchantName(), m.role().name())).toList());
    }
}
