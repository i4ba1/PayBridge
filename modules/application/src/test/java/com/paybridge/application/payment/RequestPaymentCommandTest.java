package com.paybridge.application.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.paybridge.application.payment.RequestPaymentCommand.Parse;
import java.util.List;
import org.junit.jupiter.api.Test;

class RequestPaymentCommandTest {
    private static final String INV = "00000000-0000-4000-8000-000000000401";
    private static final String KEY = "7d5c1e0a-3b7e-4f0e-9a55-0d6f3b9c2a11";
    private static final String TOKEN = "tok_sim_card_default";

    private static Parse parse(String invoice, String token, String key) {
        return RequestPaymentCommand.parse(invoice, token, key, 128);
    }

    private static List<String> invalidFields(Parse p) {
        return ((Parse.Invalid) p).problems().stream().map(PaymentAdmission.FieldProblem::field).toList();
    }

    @Test
    void aValidRequestParsesAndNormalizesUuidsToLowerCase() {
        var parsed = parse(INV.toUpperCase(), TOKEN, KEY.toUpperCase());
        var command = ((Parse.Valid) parsed).command();
        assertThat(command.invoiceId()).isEqualTo(INV);
        assertThat(command.idempotencyKey()).isEqualTo(KEY);
        assertThat(command.methodToken()).isEqualTo(TOKEN);
    }

    @Test
    void theHashIsTheDocumentedSha256AndIgnoresTheKey() {
        var a = ((Parse.Valid) parse(INV, TOKEN, KEY)).command();
        var b = ((Parse.Valid) parse(INV, TOKEN, "00000000-0000-4000-8000-0000000000ff")).command();
        // sha256("REQUEST_PAYMENT\n" + invoiceId + "\n" + methodToken), lower case hex
        assertThat(a.requestHash()).isEqualTo("c52bbebd956a94803c4c6edd2246dbace8f869ca0012972861bb8b67b8cc4289");
        assertThat(b.requestHash()).isEqualTo(a.requestHash());
    }

    @Test
    void theHashChangesWithTheInvoiceAndWithTheToken() {
        String base = ((Parse.Valid) parse(INV, TOKEN, KEY)).command().requestHash();
        String otherInvoice = ((Parse.Valid) parse("00000000-0000-4000-8000-000000000402", TOKEN, KEY)).command().requestHash();
        String otherToken = ((Parse.Valid) parse(INV, "tok_sim_transfer_default", KEY)).command().requestHash();
        assertThat(otherInvoice).isNotEqualTo(base);
        assertThat(otherToken).isNotEqualTo(base);
        assertThat(otherToken).isNotEqualTo(otherInvoice);
    }

    @Test
    void badUuidsAreRejectedPerField() {
        for (String bad : new String[] {null, "", "nope", "1-1-1-1-1", INV.replace("-", ""), " " + INV, INV + " ",
                INV.substring(1), "00000000-0000-4000-8000-00000000040g"}) {
            assertThat(invalidFields(parse(bad, TOKEN, KEY))).as("invoice %s", bad).containsExactly("invoiceId");
            assertThat(invalidFields(parse(INV, TOKEN, bad))).as("key %s", bad).containsExactly("idempotencyKey");
        }
    }

    @Test
    void hostileMethodTokensAreRejected() {
        for (String bad : new String[] {null, "", " ", "tok sim", " tok", "tok\n", "tok;DROP TABLE payment", "tok'--",
                "tök_sim_card", "tok\u0000", "x".repeat(129)}) {
            assertThat(invalidFields(parse(INV, bad, KEY))).as("token %s", bad).containsExactly("methodToken");
        }
    }

    @Test
    void aTokenAtTheConfiguredLimitIsAccepted() {
        assertThat(parse(INV, "a".repeat(128), KEY)).isInstanceOf(Parse.Valid.class);
        assertThat(RequestPaymentCommand.parse(INV, "a".repeat(33), KEY, 32)).isInstanceOf(Parse.Invalid.class);
    }

    @Test
    void everyBadFieldIsReportedTogetherWithFixedMessages() {
        var invalid = (Parse.Invalid) parse("x", "", "y");
        assertThat(invalid.problems()).extracting(PaymentAdmission.FieldProblem::field)
                .containsExactly("invoiceId", "methodToken", "idempotencyKey");
        assertThat(invalid.problems()).allSatisfy(p -> {
            assertThat(p.code()).isEqualTo("INVALID");
            assertThat(p.message()).doesNotContain("x").doesNotContain("y");
        });
    }
}
