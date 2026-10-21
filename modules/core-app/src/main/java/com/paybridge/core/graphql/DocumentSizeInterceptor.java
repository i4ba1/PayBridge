package com.paybridge.core.graphql;

import java.nio.charset.StandardCharsets;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.graphql.server.WebGraphQlInterceptor;
import org.springframework.graphql.server.WebGraphQlRequest;
import org.springframework.graphql.server.WebGraphQlResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

/** Runs before everything else: an oversized document is refused (HTTP 413) before it is parsed or authenticated. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DocumentSizeInterceptor implements WebGraphQlInterceptor {

    @Override
    public Mono<WebGraphQlResponse> intercept(WebGraphQlRequest request, Chain chain) {
        if (request.getDocument().getBytes(StandardCharsets.UTF_8).length > GraphQlLimits.MAX_DOCUMENT_BYTES) {
            return Mono.error(new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Request too large"));
        }
        return chain.next(request);
    }
}
