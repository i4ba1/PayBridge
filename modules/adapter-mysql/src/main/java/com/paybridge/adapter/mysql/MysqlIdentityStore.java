package com.paybridge.adapter.mysql;

import com.paybridge.adapter.mysql.entity.AppUserEntity;
import com.paybridge.application.port.IdentityStore;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MysqlIdentityStore implements IdentityStore {

    interface AppUserRepository extends JpaRepository<AppUserEntity, String> {
        Optional<AppUserEntity> findByUsername(String username);

        boolean existsByGlobalRole(AppUserEntity.Role role);

        @Query("select u from AppUserEntity u where (:enabled is null or u.enabled = :enabled) "
                + "and (u.username like :q or lower(u.displayName) like :q) "
                + "and (:merchantId is null or exists (select 1 from MembershipEntity m where m.userId = u.id "
                + "and m.merchantId = :merchantId)) and (:after is null or u.username > :after) order by u.username")
        List<AppUserEntity> search(@Param("enabled") Boolean enabled, @Param("merchantId") String merchantId,
                                   @Param("q") String q, @Param("after") String after, Pageable page);
    }

    private final AppUserRepository users;
    private final Clock clock;

    MysqlIdentityStore(AppUserRepository users, Clock clock) {
        this.users = users;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRecord> findByUsername(String username) {
        return users.findByUsername(username).map(MysqlIdentityStore::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserRecord> findById(String id) {
        return users.findById(id).map(MysqlIdentityStore::toRecord);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean adminExists() {
        return users.existsByGlobalRole(AppUserEntity.Role.ADMIN);
    }

    @Override
    @Transactional
    public UserRecord create(NewUser user) {
        var entity = new AppUserEntity(user.id(), user.username(), user.displayName(), user.passwordHash(),
                AppUserEntity.Role.valueOf(user.globalRole().name()), user.mustChangePassword(), clock.instant());
        try {
            return toRecord(users.saveAndFlush(entity));
        } catch (DataIntegrityViolationException e) {
            throw new DuplicateAccountException(user.username(), e);
        }
    }

    @Override
    @Transactional
    public UserRecord update(String id, UserChange change) {
        var entity = users.findById(id).orElseThrow(() -> new NoSuchElementException("no such account"));
        entity.apply(change.displayName(), change.enabled(),
                change.globalRole() == null ? null : AppUserEntity.Role.valueOf(change.globalRole().name()),
                change.passwordHash(), change.mustChangePassword(), clock.instant());
        return toRecord(users.saveAndFlush(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<UserRecord> list(UserFilter filter, int limit, String afterUsername) {
        String q = filter.query() == null || filter.query().isBlank() ? "%"
                : "%" + filter.query().toLowerCase(Locale.ROOT).replace("%", "").replace("_", "") + "%";
        return users.search(filter.enabled(), filter.merchantId(), q, afterUsername, PageRequest.of(0, limit)).stream()
                .map(MysqlIdentityStore::toRecord).toList();
    }

    private static UserRecord toRecord(AppUserEntity e) {
        return new UserRecord(e.getId(), e.getUsername(), e.getDisplayName(), e.getPasswordHash(),
                GlobalRole.valueOf(e.getGlobalRole().name()), e.isEnabled(), e.isMustChangePassword(),
                e.getCreatedAt(), e.getVersion());
    }
}
