package com.paybridge.core.graphql;

import graphql.ErrorType;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import graphql.execution.DataFetcherExceptionHandler;
import graphql.execution.DataFetcherExceptionHandlerParameters;
import graphql.execution.DataFetcherExceptionHandlerResult;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Replaces DGS's default handler, which returns {@code exceptionClass: message} to the client and logs the whole cause
 * (for a database failure that is SQL text and bound values). Spec section 5: the client gets a fixed message with the
 * correlation id, the log gets the exception class and the correlation id only. Domain outcomes never reach this
 * handler: they are typed results (OperationProblem, null for a non disclosing lookup). The one thrown outcome, an
 * authorization denial on a non null field, keeps its problem code as the message.
 */
@Component
public class SanitizingExceptionHandler implements DataFetcherExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(SanitizingExceptionHandler.class);
    static final String INTERNAL_ERROR = "Internal error.";

    @Override
    public CompletableFuture<DataFetcherExceptionHandlerResult> handleException(DataFetcherExceptionHandlerParameters params) {
        Throwable exception = unwrap(params.getException());
        var env = params.getDataFetchingEnvironment();
        GraphQLError error;
        if (exception instanceof AuthorizationInstrumentation.AccessDenied denied) {
            error = GraphqlErrorBuilder.newError(env).message(denied.code()).errorType(ErrorType.DataFetchingException).build();
        } else {
            String correlation = env.getGraphQlContext().get(ServiceContextInterceptor.CORRELATION_ID);
            log.error("unexpected failure in a GraphQL resolver: {} correlationId={}", exception.getClass().getName(),
                    correlation == null ? "unknown" : correlation);
            String message = correlation == null ? INTERNAL_ERROR : INTERNAL_ERROR + " Correlation id: " + correlation;
            error = GraphqlErrorBuilder.newError(env).message(message).errorType(ErrorType.DataFetchingException).build();
        }
        return CompletableFuture.completedFuture(DataFetcherExceptionHandlerResult.newResult().error(error).build());
    }

    private static Throwable unwrap(Throwable t) {
        Throwable current = t;
        while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
