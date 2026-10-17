package com.paybridge.core.graphql;

import com.paybridge.core.graphql.model.IdentityTypes.OperationProblem;
import com.paybridge.core.identity.IdentityException.Code;
import com.paybridge.core.security.Authorizer;
import com.paybridge.core.security.Authorizer.Denied;
import com.paybridge.core.security.Authorizer.Granted;
import com.paybridge.core.security.Authorizer.Requirement;
import com.paybridge.core.security.ServiceContextVerifier;
import graphql.execution.instrumentation.InstrumentationState;
import graphql.execution.instrumentation.SimplePerformantInstrumentation;
import graphql.execution.instrumentation.parameters.InstrumentationFieldFetchParameters;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironmentImpl;
import graphql.schema.GraphQLFieldDefinition;
import graphql.schema.GraphQLNonNull;
import graphql.schema.GraphQLType;
import graphql.schema.GraphQLTypeUtil;
import graphql.schema.GraphQLUnionType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Enforces the schema's {@code @auth} directive on every root field (API contract 5.1 and 5.3). A root field without the
 * directive is refused, so a forgotten directive fails closed. Denials are typed: a union result gets an
 * OperationProblem, a nullable lookup gets null (non disclosing), anything else is an error.
 */
@Component
public class AuthorizationInstrumentation extends SimplePerformantInstrumentation {
    private static final Logger log = LoggerFactory.getLogger(AuthorizationInstrumentation.class);

    private final Authorizer authorizer;

    public AuthorizationInstrumentation(Authorizer authorizer) {
        this.authorizer = authorizer;
    }

    @Override
    public DataFetcher<?> instrumentDataFetcher(DataFetcher<?> dataFetcher, InstrumentationFieldFetchParameters parameters,
                                                InstrumentationState state) {
        String parent = ((graphql.schema.GraphQLNamedType) parameters.getEnvironment().getParentType()).getName();
        if (!parent.equals("Query") && !parent.equals("Mutation")) {
            return dataFetcher;
        }
        GraphQLFieldDefinition field = parameters.getExecutionStepInfo().getFieldDefinition();
        Requirement requirement = requirement(field);
        return env -> {
            var graphQlContext = env.getGraphQlContext();
            ServiceContextVerifier.Claims claims = graphQlContext.get(ServiceContextInterceptor.CLAIMS);
            String correlation = graphQlContext.getOrDefault(ServiceContextInterceptor.CORRELATION_ID, "unknown");
            if (requirement == null || claims == null) {
                log.warn("root field {} has no usable @auth directive (found: {}) or service context (present: {}); refused",
                        field.getName(), requirement != null, claims != null);
                return denied(field, Code.FORBIDDEN);
            }
            var decision = authorizer.authorize(claims, correlation, requirement);
            if (decision instanceof Denied d) {
                return denied(field, d.code());
            }
            var granted = (Granted) decision;
            return dataFetcher.get(DataFetchingEnvironmentImpl.newDataFetchingEnvironment(env).localContext(granted).build());
        };
    }

    static Requirement requirement(GraphQLFieldDefinition field) {
        try {
            String requires = null;
            String scope = null;
            var applied = field.getAppliedDirective("auth");
            if (applied != null) {
                requires = literal(applied.getArgument("requires"));
                scope = literal(applied.getArgument("scope"));
            } else {
                var legacy = field.getDirective("auth");
                if (legacy != null) {
                    requires = literal(legacy.getArgument("requires"));
                    scope = literal(legacy.getArgument("scope"));
                }
            }
            if (requires == null) {
                return null;
            }
            return new Requirement(field.getName(), Authorizer.Requires.valueOf(requires),
                    scope == null ? Authorizer.Scope.MERCHANT : Authorizer.Scope.valueOf(scope));
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Directive arguments arrive as an applied value, a legacy argument, or an AST literal; read all three. */
    @SuppressWarnings("deprecation")
    private static String literal(Object argument) {
        if (argument == null) {
            return null;
        }
        Object value;
        if (argument instanceof graphql.schema.GraphQLAppliedDirectiveArgument a) {
            value = a.getArgumentValue().getValue();
        } else if (argument instanceof graphql.schema.GraphQLArgument a) {
            value = a.getArgumentValue().isSet() ? a.getArgumentValue().getValue() : a.getArgumentDefaultValue().getValue();
        } else {
            value = argument;
        }
        if (value instanceof graphql.language.EnumValue e) {
            return e.getName();
        }
        return value == null ? null : String.valueOf(value);
    }

    private static Object denied(GraphQLFieldDefinition field, Code code) {
        GraphQLType type = field.getType();
        GraphQLType unwrapped = GraphQLTypeUtil.unwrapAll(type);
        if (unwrapped instanceof GraphQLUnionType) {
            return OperationProblem.of(code.name(), message(code));
        }
        if (!(type instanceof GraphQLNonNull)) {
            return null;                    // not visible: indistinguishable from missing
        }
        throw new IllegalStateException(code.name());
    }

    private static String message(Code code) {
        return switch (code) {
            case UNAUTHENTICATED -> "Sign in again.";
            case PASSWORD_CHANGE_REQUIRED -> "Change the temporary password first.";
            default -> "You are not allowed to do that.";
        };
    }
}
