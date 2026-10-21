package com.paybridge.application.port;

import java.util.function.Supplier;

/**
 * Non overwriting writes used by the seed role (DMR section 3). Every insert is "key lookup first, then insert with a
 * duplicate key guard"; an existing row is never updated. Each method returns true only when it created the row.
 * Nothing here touches payment, payment_request, events or tasks, and nothing changes an invoice after creation.
 */
public interface SeedStore {

    /** Runs the work while holding the named database lock; returns empty when the lock was not obtained in time. */
    <T> java.util.Optional<T> withLock(String name, int timeoutSeconds, Supplier<T> work);

    <T> T inTransaction(Supplier<T> work);

    boolean insertMethod(String methodCode, String displayName, String settlement);

    boolean insertMethodToken(String token, String methodCode, String description);

    boolean ensureBootstrapRow();

    /** Re-arms the first-run token while no administrator exists. Returns the number of rows changed (0 or 1). */
    int rearmBootstrapToken(String tokenHashHex, int ttlHours);

    boolean insertMerchant(String id, String code, String name, String currency);

    boolean insertInvoice(String id, String merchantId, String reference, String description, long amountMinor,
                          String currency);

    boolean insertMerchantMethod(String merchantId, String methodCode);

    boolean insertMerchantQuota(String merchantId, int ratePerSecond, int burstCapacity);

    boolean insertMembership(String id, String userId, String merchantId, String role);

    boolean userExists(String username, String id);
}
