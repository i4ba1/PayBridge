package com.paybridge.application.port;

import java.util.List;
import java.util.Optional;

/** Authoritative merchant memberships. Callers never cache these beyond one request. */
public interface MembershipStore {

    enum Role { OPERATOR, VIEWER }

    record Grant(String merchantId, Role role) {}

    record MembershipView(String merchantId, String merchantName, String currency, Role role) {}

    record MerchantView(String id, String code, String name, String currency, boolean active) {}

    List<MembershipView> membershipsOf(String userId);

    Optional<Role> roleOf(String userId, String merchantId);

    Optional<MerchantView> findMerchant(String merchantId);

    /** Makes the user's memberships equal to the given grants. Returns one human readable change per difference. */
    List<Change> replace(String userId, List<Grant> grants);

    record Change(String membershipId, String merchantId, Role oldRole, Role newRole) {}
}
