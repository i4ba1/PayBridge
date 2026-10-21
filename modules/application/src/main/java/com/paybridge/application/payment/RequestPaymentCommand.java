package com.paybridge.application.payment;

import com.paybridge.application.payment.PaymentAdmission.FieldProblem;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** A validated, normalized requestPayment input. UUIDs are canonical lower case so one key has one spelling. */
public record RequestPaymentCommand(String invoiceId, String methodToken, String idempotencyKey) {
    public static final String OPERATION = "REQUEST_PAYMENT";
    private static final Pattern UUID_FORM =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern TOKEN_FORM = Pattern.compile("[A-Za-z0-9_.-]+");

    public sealed interface Parse {
        record Valid(RequestPaymentCommand command) implements Parse {}

        record Invalid(List<FieldProblem> problems) implements Parse {}
    }

    public static Parse parse(String invoiceId, String methodToken, String idempotencyKey, int tokenMaxLength) {
        var problems = new ArrayList<FieldProblem>();
        if (!isUuid(invoiceId)) {
            problems.add(new FieldProblem("invoiceId", "INVALID", "Must be a UUID."));
        }
        if (methodToken == null || methodToken.isEmpty() || methodToken.length() > tokenMaxLength
                || !TOKEN_FORM.matcher(methodToken).matches()) {
            problems.add(new FieldProblem("methodToken", "INVALID", "Is not an accepted token."));
        }
        if (!isUuid(idempotencyKey)) {
            problems.add(new FieldProblem("idempotencyKey", "INVALID", "Must be a UUID."));
        }
        if (!problems.isEmpty()) {
            return new Parse.Invalid(List.copyOf(problems));
        }
        return new Parse.Valid(new RequestPaymentCommand(invoiceId.toLowerCase(Locale.ROOT), methodToken,
                idempotencyKey.toLowerCase(Locale.ROOT)));
    }

    private static boolean isUuid(String value) {
        return value != null && UUID_FORM.matcher(value).matches();
    }

    /** SHA-256 over "REQUEST_PAYMENT", invoice id and method token, separated by a newline, as lower case hex (DMR 6.1). */
    public String requestHash() {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((OPERATION + "\n" + invoiceId + "\n" + methodToken).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
