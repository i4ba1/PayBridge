package com.paybridge.core.seed;

import com.paybridge.application.port.IdentityStore;
import com.paybridge.application.port.SeedStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Idempotent seed (DMR section 3). Inserts what is missing and never updates or deletes: payments, requests, events,
 * invoice amounts and active_payment_id are never touched, and an existing account keeps its password.
 * The report counts rows per table; it holds no values.
 */
public class DemoSeeder {
    private static final Logger log = LoggerFactory.getLogger(DemoSeeder.class);
    static final String LOCK = "pb.seed";
    private static final String ID_PREFIX = "00000000-0000-4000-8000-";
    private static final String ALNUM = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final int PASSWORD_LENGTH = 20;

    public record Options(boolean demo, String bootstrapToken, int bootstrapTtlHours) {}

    public record Counts(int inserted, int skipped) {}

    public record Report(boolean lockObtained, Map<String, Counts> tables, int bootstrapRearmed) {
        public int inserted(String table) {
            var c = tables.get(table);
            return c == null ? 0 : c.inserted();
        }
    }

    private record MerchantSeed(String id, String code, String name, String currency, List<InvoiceSeed> invoices) {}

    private record InvoiceSeed(String id, String reference, String description, long amountMinor) {}

    private record UserSeed(String id, String username, String displayName, String membershipId, String merchantId,
                            String role) {}

    private static String id(int n) {
        return ID_PREFIX + String.format("%012d", n);
    }

    private static final String MERCHANT_A = id(301);
    private static final String MERCHANT_B = id(302);

    private static final List<MerchantSeed> MERCHANTS = List.of(
            new MerchantSeed(MERCHANT_A, "warung-nusantara", "Warung Nusantara", "IDR", List.of(
                    new InvoiceSeed(id(401), "INV-A-001", "Catering order", 150_000),
                    new InvoiceSeed(id(402), "INV-A-002", "Monthly supplies", 275_000),
                    new InvoiceSeed(id(403), "INV-A-003", "Delivery fee", 50_000),
                    new InvoiceSeed(id(404), "INV-A-004", "Equipment deposit", 1_200_000),
                    new InvoiceSeed(id(405), "INV-A-005", "Subscription", 99_000))),
            new MerchantSeed(MERCHANT_B, "harbor-coffee", "Harbor Coffee Co", "USD", List.of(
                    new InvoiceSeed(id(411), "INV-B-001", "Espresso beans", 2_500),
                    new InvoiceSeed(id(412), "INV-B-002", "Bulk order", 12_990),
                    new InvoiceSeed(id(413), "INV-B-003", "Shipping", 500),
                    new InvoiceSeed(id(414), "INV-B-004", "Cafe equipment", 75_000),
                    new InvoiceSeed(id(415), "INV-B-005", "Gift box", 1_999))));

    private static final List<UserSeed> USERS = List.of(
            new UserSeed(id(501), "demo-operator-a", "Demo Operator A", id(601), MERCHANT_A, "OPERATOR"),
            new UserSeed(id(502), "demo-viewer-a", "Demo Viewer A", id(602), MERCHANT_A, "VIEWER"),
            new UserSeed(id(503), "demo-operator-b", "Demo Operator B", id(603), MERCHANT_B, "OPERATOR"),
            new UserSeed(id(504), "demo-viewer-b", "Demo Viewer B", id(604), MERCHANT_B, "VIEWER"));

    private static final List<String[]> METHODS = List.of(
            new String[] {"SIMULATED_CARD", "Simulated card", "IMMEDIATE"},
            new String[] {"SIMULATED_TRANSFER", "Simulated bank transfer", "DELAYED"});
    private static final List<String[]> TOKENS = List.of(
            new String[] {"tok_sim_card_default", "SIMULATED_CARD", "Default simulated card fixture"},
            new String[] {"tok_sim_card_alt", "SIMULATED_CARD", "Second simulated card fixture, identical behavior"},
            new String[] {"tok_sim_transfer_default", "SIMULATED_TRANSFER", "Default simulated transfer fixture"});

    private final SeedStore seed;
    private final IdentityStore identity;
    private final PasswordEncoder encoder;
    private final CredentialSink credentials;
    private final SecureRandom random = new SecureRandom();

    public DemoSeeder(SeedStore seed, IdentityStore identity, PasswordEncoder encoder, CredentialSink credentials) {
        this.seed = seed;
        this.identity = identity;
        this.encoder = encoder;
        this.credentials = credentials;
    }

    /** Runs the seed under the database lock. Returns a report whose lockObtained is false when another seed runs. */
    public Report run(Options options) {
        var result = seed.withLock(LOCK, 30, () -> apply(options));
        if (result.isEmpty()) {
            log.info("seed skipped: another seed holds the lock");
            return new Report(false, Map.of(), 0);
        }
        return result.get();
    }

    private Report apply(Options options) {
        var tally = new Tally();
        for (String[] m : METHODS) {
            tally.add("method_catalog", seed.insertMethod(m[0], m[1], m[2]));
        }
        for (String[] t : TOKENS) {
            tally.add("method_token", seed.insertMethodToken(t[0], t[1], t[2]));
        }
        tally.add("bootstrap_state", seed.ensureBootstrapRow());

        int rearmed = 0;
        if (options.bootstrapToken() != null && !options.bootstrapToken().isBlank()) {
            rearmed = seed.rearmBootstrapToken(sha256Hex(options.bootstrapToken()), options.bootstrapTtlHours());
        }

        if (options.demo()) {
            for (MerchantSeed m : MERCHANTS) {
                tally.add("merchant", seed.insertMerchant(m.id(), m.code(), m.name(), m.currency()));
            }
            for (MerchantSeed m : MERCHANTS) {
                for (String[] method : METHODS) {
                    tally.add("merchant_method", seed.insertMerchantMethod(m.id(), method[0]));
                }
                tally.add("merchant_quota_setting", seed.insertMerchantQuota(m.id(), 20, 40));
                seed.inTransaction(() -> {
                    for (InvoiceSeed i : m.invoices()) {
                        tally.add("invoice", seed.insertInvoice(i.id(), m.id(), i.reference(), i.description(),
                                i.amountMinor(), m.currency()));
                    }
                    return null;
                });
            }
            seedUsers(tally);
        }
        log.info("seed complete: {}", tally.counts());
        return new Report(true, tally.counts(), rearmed);
    }

    private void seedUsers(Tally tally) {
        // Passwords exist only for accounts created now. They are handed to the sink BEFORE the rows are inserted, so a
        // failed sink creates no account whose password nobody has.
        var fresh = new ArrayList<UserSeed>();
        var passwords = new LinkedHashMap<String, String>();
        for (UserSeed u : USERS) {
            if (seed.userExists(u.username(), u.id())) {
                tally.add("app_user", false);
            } else {
                fresh.add(u);
                passwords.put(u.username(), newPassword());
            }
        }
        if (!fresh.isEmpty()) {
            credentials.store(passwords);
            for (UserSeed u : fresh) {
                identity.create(new IdentityStore.NewUser(u.id(), u.username(), u.displayName(),
                        encoder.encode(passwords.get(u.username())), IdentityStore.GlobalRole.USER, false));
                tally.add("app_user", true);
            }
        }
        for (UserSeed u : USERS) {
            tally.add("membership", seed.insertMembership(u.membershipId(), u.id(), u.merchantId(), u.role()));
        }
    }

    private String newPassword() {
        var sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(ALNUM.charAt(random.nextInt(ALNUM.length())));
        }
        return sb.toString();
    }

    static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class Tally {
        private final Map<String, int[]> counts = new LinkedHashMap<>();

        void add(String table, boolean inserted) {
            counts.computeIfAbsent(table, k -> new int[2])[inserted ? 0 : 1]++;
        }

        Map<String, Counts> counts() {
            var out = new LinkedHashMap<String, Counts>();
            counts.forEach((k, v) -> out.put(k, new Counts(v[0], v[1])));
            return out;
        }
    }
}
