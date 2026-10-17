package com.paybridge.adapter.mysql;

import com.paybridge.adapter.mysql.entity.MembershipEntity;
import com.paybridge.adapter.mysql.entity.MerchantEntity;
import com.paybridge.application.port.MembershipStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MysqlMembershipStore implements MembershipStore {

    interface MembershipRepository extends JpaRepository<MembershipEntity, String> {
        List<MembershipEntity> findByUserId(String userId);

        Optional<MembershipEntity> findByUserIdAndMerchantId(String userId, String merchantId);
    }

    interface MerchantRepository extends JpaRepository<MerchantEntity, String> {}

    private final MembershipRepository memberships;
    private final MerchantRepository merchants;
    private final Clock clock;

    MysqlMembershipStore(MembershipRepository memberships, MerchantRepository merchants, Clock clock) {
        this.memberships = memberships;
        this.merchants = merchants;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public List<MembershipView> membershipsOf(String userId) {
        var out = new ArrayList<MembershipView>();
        for (MembershipEntity m : memberships.findByUserId(userId)) {
            merchants.findById(m.getMerchantId()).ifPresent(merchant -> out.add(new MembershipView(merchant.getId(),
                    merchant.getName(), merchant.getCurrency(), Role.valueOf(m.getRole().name()))));
        }
        out.sort(java.util.Comparator.comparing(MembershipView::merchantName));
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Role> roleOf(String userId, String merchantId) {
        return memberships.findByUserIdAndMerchantId(userId, merchantId).map(m -> Role.valueOf(m.getRole().name()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MerchantView> findMerchant(String merchantId) {
        return merchants.findById(merchantId).map(m -> new MerchantView(m.getId(), m.getCode(), m.getName(),
                m.getCurrency(), m.getStatus() == MerchantEntity.Status.ACTIVE));
    }

    @Override
    @Transactional
    public List<Change> replace(String userId, List<Grant> grants) {
        Map<String, Role> wanted = new HashMap<>();
        for (Grant g : grants) {
            wanted.put(g.merchantId(), g.role());
        }
        var changes = new ArrayList<Change>();
        for (MembershipEntity existing : memberships.findByUserId(userId)) {
            Role old = Role.valueOf(existing.getRole().name());
            Role next = wanted.remove(existing.getMerchantId());
            if (next == null) {
                memberships.delete(existing);
                changes.add(new Change(existing.getId(), existing.getMerchantId(), old, null));
            } else if (next != old) {
                existing.changeRole(MembershipEntity.Role.valueOf(next.name()), clock.instant());
                changes.add(new Change(existing.getId(), existing.getMerchantId(), old, next));
            }
        }
        for (var added : wanted.entrySet()) {
            var created = new MembershipEntity(UUID.randomUUID().toString(), userId, added.getKey(),
                    MembershipEntity.Role.valueOf(added.getValue().name()), clock.instant());
            memberships.save(created);
            changes.add(new Change(created.getId(), added.getKey(), null, added.getValue()));
        }
        memberships.flush();
        return changes;
    }
}
