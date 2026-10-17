package com.paybridge.core.graphql;

import com.paybridge.core.security.ServiceContextVerifier;
import graphql.language.Document;
import graphql.language.OperationDefinition;
import graphql.parser.Parser;
import java.util.Map;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/**
 * First gate for every GraphQL request: verifies the signed service context and binds it to the single operation in the
 * document (API contract 7.3 steps 1 to 3). A failure is HTTP 401 with a generic body; the reason goes to the log and
 * never to the caller. Verified claims are placed in the GraphQL context for the field level authorization.
 */
@Component
public class ServiceContextInterceptor implements WebGraphQlInterceptor {
    public static final String CLAIMS = "pb.claims";
    public static final String CORRELATION_ID = "pb.correlationId";
    public static final String CLIENT_ADDRESS = "pb.clientAddress";
    static final String CORRELATION_HEADER = "X-Correlation-Id";
    static final String CLIENT_ADDRESS_HEADER = "X-Client-Address";
    private static final Pattern SAFE_ADDRESS = Pattern.compile("^[0-9a-fA-F.:]{3,45}$");
    private static final Logger log = LoggerFactory.getLogger(ServiceContextInterceptor.class);

    private final ServiceContextVerifier verifier;

    public ServiceContextInterceptor(ServiceContextVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        ServiceContextVerifier.Claims claims;
        try {
            claims = verifier.verify(request.getHeaders().getFirst("Authorization"));
            checkOperation(request.getDocument(), claims.operation());
        } catch (ServiceContextVerifier.Rejected r) {
            log.warn("service context rejected: {}", r.reason());
            return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Unauthorized"));
        }
        String correlation = request.getHeaders().getFirst(CORRELATION_HEADER);
        if (correlation == null || !correlation.matches("^[A-Za-z0-9._:-]{1,64}$")) {
            correlation = claims.requestId();
        }
        String address = request.getHeaders().getFirst(CLIENT_ADDRESS_HEADER);
        if (address == null || !SAFE_ADDRESS.matcher(address).matches()) {
            address = "unknown";
        }
        final String corr = correlation, addr = address;
        final var verified = claims;
        request.configureExecutionInput((input, builder) -> builder
                .graphQLContext(Map.of(CLAIMS, verified, CORRELATION_ID, corr, CLIENT_ADDRESS, addr)).build());
        return chain.next(request);
    }

    /** The document must hold exactly one operation, and its name must be the one the token authorizes. */
    static void checkOperation(String document, String authorized) {
        Document parsed;
        try {
            parsed = Parser.parse(document);
        } catch (RuntimeException e) {
            throw new ServiceContextVerifier.Rejected(ServiceContextVerifier.Reason.MALFORMED);
        }
        var operations = parsed.getDefinitionsOfType(OperationDefinition.class);
        if (operations.size() != 1 || !authorized.equals(operations.get(0).getName())) {
            throw new ServiceContextVerifier.Rejected(ServiceContextVerifier.Reason.OPERATION_NOT_ALLOWED);
        }
    }
}
