package com.paybridge.core.identity;

import com.paybridge.application.port.IdentityStore.GlobalRole;
import com.paybridge.application.port.IdentityStore.UserRecord;
import com.paybridge.application.port.MembershipStore.MembershipView;
import java.util.List;

/** An account with memberships resolved from the authoritative tables at this moment. */
public record UserView(UserRecord user, List<MembershipView> memberships) {
    public boolean admin() {
        return user.globalRole() == GlobalRole.ADMIN;
    }
}
