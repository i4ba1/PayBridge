package com.paybridge.it.admission;

import static com.paybridge.core.graphql.GraphQlLimits.MAX_DOCUMENT_BYTES;
import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GraphQlLimitsIT extends CoreHttpIT {
    String merchant;
    String viewer;

    @BeforeEach
    void world() {
        merchant = merchant("acme", "IDR");
        viewer = user("viewer", GlobalRole.USER, false);
        member(viewer, merchant, "VIEWER");
    }

    private static String aliases(int count) {
        return IntStream.range(0, count).mapToObj(i -> "a" + i + ": paymentMethods { code } ").collect(Collectors.joining());
    }

    @Test
    void aDocumentOverEightKiBIsRefusedBeforeItIsParsed() {
        String padding = "#" + "x".repeat(9_000);
        var response = call(viewer, merchant, "Big", "query Big { me { id } } " + padding, Map.of());
        assertThat(response.status()).isEqualTo(413);
    }

    @Test
    void anAmplifiedDocumentIsRefusedByComplexityAndNeverReachesAResolver() {
        // paymentMethods { code } = 1 + 5 + 1 = 7, so 50 aliases = 350 > 300. The document is well under 8 KiB.
        String document = "query Many { " + aliases(50) + "}";
        assertThat(document.length()).isLessThan(MAX_DOCUMENT_BYTES);
        var response = call(viewer, merchant, "Many", document, Map.of());
        assertThat(response.status()).isEqualTo(200);
        assertThat(response.body().path("errors").toString()).contains("complexity");
        assertThat(response.data().isMissingNode() || response.data().isNull()).isTrue();
        assertThat(auditCount("PAYMENT_ADMITTED")).isZero();
    }

    @Test
    void aDocumentJustUnderTheComplexityLimitIsNotRefusedForComplexity() {
        String document = "query Many { " + aliases(40) + "}";   // 280
        var response = call(viewer, merchant, "Many", document, Map.of());
        assertThat(response.body().path("errors").toString()).doesNotContain("complexity");
    }
}
