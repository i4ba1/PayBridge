package com.paybridge.adapter.mysql;

import com.paybridge.adapter.mysql.entity.AppUserEntity;
import com.paybridge.application.port.IdentityStore;
import java.time.Clock;
import java.util.Optional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MysqlIdentityStore implements IdentityStore {

    interface AppUserRepository extends JpaRepository<AppUserEntity, String> {
        Optional<AppUserEntity> findByUsername(String username);

        boolean existsByGlobalRole(AppUserEntity.Role role);
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

    private static UserRecord toRecord(AppUserEntity e) {
        return new UserRecord(e.getId(), e.getUsername(), e.getDisplayName(), e.getPasswordHash(),
                GlobalRole.valueOf(e.getGlobalRole().name()), e.isEnabled(), e.isMustChangePassword(),
                e.getCreatedAt(), e.getVersion());
    }
}
