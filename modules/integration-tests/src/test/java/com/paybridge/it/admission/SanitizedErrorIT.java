package com.paybridge.it.admission;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.read.ListAppender;
import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.it.support.CoreHttpIT;
import com.paybridge.it.support.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Spec section 5: an unexpected failure in a resolver reaches the client as a fixed message with the correlation id, and
 * the log carries only the exception class and the correlation id. A root trigger makes every payment insert fail with a
 * database error, so the failure carries SQL text, table and column names that must not leak.
 */
class SanitizedErrorIT extends CoreHttpIT {
    private static final String TOKEN = "tok_sim_card_default";
    private static final String CORRELATION = "corr-sanitize-1";
    private static final String TRIGGER_TEXT = "t07-sabotage-detail";
    private static final String REQUEST = "mutation RequestPayment($input: RequestPaymentInput!) { requestPayment(input: $input) "
            + "{ __typename ... on PaymentAccepted { replayed payment { id } } " + PROBLEM + " } }";

    private ListAppender<ILoggingEvent> logs;
    private Logger rootLogger;
    String merchant;
    String operator;
    String invoice;

    @BeforeEach
    void world() {
        merchant = merchant("acme", "IDR");
        operator = user("operator", GlobalRole.USER, false);
        member(operator, merchant, "OPERATOR");
        invoice = Fixtures.invoice(rootJdbc, merchant, 150_000, "IDR");
        Fixtures.method(rootJdbc, merchant, "SIMULATED_CARD", TOKEN);
        rootJdbc.execute("CREATE TRIGGER t07_sanitize BEFORE INSERT ON payment FOR EACH ROW "
                + "SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = '" + TRIGGER_TEXT + "'");
        rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs = new ListAppender<>();
        logs.start();
        rootLogger.addAppender(logs);
    }

    @AfterEach
    void dropTrigger() {
        rootLogger.detachAppender(logs);
        rootJdbc.execute("DROP TRIGGER IF EXISTS t07_sanitize");
    }

    @Test
    void anAuthorizationDenialOnANonNullFieldKeepsItsProblemCodeAndIsNotAnInternalError() {
        var response = call(Fixtures.uuid(), null, "MyMerchants", "query MyMerchants { myMerchants { id } }", Map.of());

        var errors = response.body().path("errors");
        assertThat(errors).as(response.body().toString()).hasSize(1);
        assertThat(errors.get(0).path("message").asText()).isEqualTo("UNAUTHENTICATED");
        assertThat(List.copyOf(logs.list)).noneMatch(e -> e.getLoggerName().endsWith("SanitizingExceptionHandler"));
    }

    @Test
    void aDatabaseFailureInRequestPaymentIsAFixedErrorAndASanitizedLogLine() {
        var response = post(token(operator, merchant, "RequestPayment"), REQUEST,
                Map.of("input", Map.of("invoiceId", invoice, "methodToken", TOKEN, "idempotencyKey", Fixtures.uuid())),
                "RequestPayment", Map.of("X-Correlation-Id", CORRELATION));

        assertThat(response.status()).isEqualTo(200);
        var errors = response.body().path("errors");
        assertThat(errors).as(response.body().toString()).hasSize(1);
        String message = errors.get(0).path("message").asText();
        assertThat(message).isEqualTo("Internal error. Correlation id: " + CORRELATION);
        String body = response.body().toString();
        for (String leak : List.of("SQL", "INSERT", "payment_request", "merchant_id", "amount_minor", "invoice", "Exception",
                TRIGGER_TEXT, invoice, merchant)) {
            assertThat(body).as("response body leaks %s", leak).doesNotContainIgnoringCase(leak);
        }
        assertThat(rootJdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class)).isZero();

        var lines = List.copyOf(logs.list);
        assertThat(lines).as("the failure is logged once with its class and correlation id")
                .anyMatch(e -> e.getFormattedMessage().contains("UncategorizedSQLException")
                        && e.getFormattedMessage().contains(CORRELATION));
        for (ILoggingEvent e : lines) {
            String text = e.getFormattedMessage() + " "
                    + (e.getThrowableProxy() == null ? "" : ThrowableProxyUtil.asString(e.getThrowableProxy()));
            for (String leak : List.of("INSERT", "SQL [", "payment_request", "amount_minor", TRIGGER_TEXT, invoice)) {
                assertThat(text).as("log line from %s leaks %s", e.getLoggerName(), leak).doesNotContain(leak);
            }
        }
    }
}
