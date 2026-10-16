package com.paybridge.core.migrate;

import com.paybridge.adapter.mysql.migration.SchemaVersion;
import com.paybridge.adapter.mysql.migration.TableGrants;
import com.paybridge.core.config.Roles;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Role migrate: applies Flyway migrations and the table grants once, then the process exits (see CoreApplication).
 * Any failure propagates, which gives a non zero exit code and fails the Helm hook.
 */
@Component
public class MigrationRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(MigrationRunner.class);

    private final DataSource ddlDataSource;
    private final Environment env;

    MigrationRunner(DataSource writerDataSource, Environment env) {
        this.ddlDataSource = writerDataSource;
        this.env = env;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!Roles.from(env).has("migrate")) {
            return;
        }
        int applied = SchemaVersion.migrate(ddlDataSource);
        int grants = TableGrants.apply(ddlDataSource);
        var status = SchemaVersion.check(ddlDataSource);
        log.info("migrate complete: {} migration(s) applied, schema version {}, {} grant statement(s)", applied,
                status.applied(), grants);
        if (!status.current()) {
            throw new IllegalStateException("schema is not current after migrate: expected " + status.expected()
                    + ", applied " + status.applied());
        }
    }
}
