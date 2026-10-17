package com.paybridge.it.support;

import com.paybridge.adapter.mysql.migration.SchemaVersion;
import com.paybridge.adapter.mysql.migration.TableGrants;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.List;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One MySQL 8.4 container per test JVM, configured like the lab chart: utf8mb4 and a +00:00 server and session time
 * zone. The image is the digest pinned in ops/toolchain.lock.yaml. The least privilege accounts mirror the lab
 * bootstrap Job, including GRANT OPTION for pb_migrate on the schema only.
 */
public final class MysqlSupport {
    public static final String SCHEMA = "paybridge";
    public static final String MIGRATE_USER = "pb_migrate";
    public static final String CORE_USER = "pb_core";
    public static final String READER_USER = "pb_reader";
    public static final String METRICS_USER = "pb_metrics";
    /** Test only throwaway credentials for a container that lives for the length of the test run. */
    public static final String PASSWORD = "test-only-password";

    private static final MySQLContainer MYSQL = start();

    private MysqlSupport() {}

    private static MySQLContainer start() {
        var container = new MySQLContainer(DockerImageName.parse(pinnedImage()).asCompatibleSubstituteFor("mysql"))
                .withDatabaseName(SCHEMA)
                .withCommand("--character-set-server=utf8mb4", "--default-time-zone=+00:00",
                        "--gtid-mode=ON", "--enforce-gtid-consistency=ON", "--max-connections=300");
        container.start();
        return container;
    }

    private static String pinnedImage() {
        return pinnedImage("mysql");
    }

    /** The digest pinned for a lab image in ops/toolchain.lock.yaml, for example mysql or redis. */
    public static String pinnedImage(String name) {
        try {
            var lock = Files.readString(findLock());
            var m = Pattern.compile("(?m)^\\s+" + name + ":\\s+(" + name + "@sha256:[0-9a-f]{64})").matcher(lock);
            if (!m.find()) {
                throw new IllegalStateException("no " + name + " image in ops/toolchain.lock.yaml");
            }
            return m.group(1);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Path findLock() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            Path candidate = dir.resolve("ops/toolchain.lock.yaml");
            if (Files.exists(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("ops/toolchain.lock.yaml not found above " + Path.of("").toAbsolutePath());
    }

    public static String jdbcUrl() {
        return "jdbc:mysql://" + MYSQL.getHost() + ":" + MYSQL.getMappedPort(3306) + "/" + SCHEMA
                + "?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true";
    }

    public static DataSource dataSource(String user) {
        return dataSource(user, PASSWORD, jdbcUrl(), 8);
    }

    public static DataSource rootDataSource() {
        return dataSource("root", MYSQL.getPassword(), jdbcUrl(), 4);
    }

    private static DataSource dataSource(String user, String password, String url, int max) {
        var config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(max);
        config.setConnectionInitSql("SET time_zone = '+00:00'");
        return new HikariDataSource(config);
    }

    /** Drops and recreates the schema and the accounts, leaving an empty database ready for pb_migrate. */
    public static synchronized void resetSchema() {
        // Connects without a default schema because the schema is dropped here.
        String serverUrl = jdbcUrl().replace("/" + SCHEMA + "?", "/?");
        try (var ds = (HikariDataSource) dataSource("root", MYSQL.getPassword(), serverUrl, 1); var con = ds.getConnection(); var st = con.createStatement()) {
            List<String> statements = List.of(
                    "DROP DATABASE IF EXISTS " + SCHEMA,
                    "CREATE DATABASE " + SCHEMA + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci",
                    "CREATE USER IF NOT EXISTS '" + MIGRATE_USER + "'@'%' IDENTIFIED BY '" + PASSWORD + "'",
                    "CREATE USER IF NOT EXISTS '" + CORE_USER + "'@'%' IDENTIFIED BY '" + PASSWORD + "'",
                    "CREATE USER IF NOT EXISTS '" + READER_USER + "'@'%' IDENTIFIED BY '" + PASSWORD + "'",
                    "CREATE USER IF NOT EXISTS '" + METRICS_USER + "'@'%' IDENTIFIED BY '" + PASSWORD + "'",
                    "GRANT ALL PRIVILEGES ON " + SCHEMA + ".* TO '" + MIGRATE_USER + "'@'%' WITH GRANT OPTION",
                    "GRANT SELECT ON " + SCHEMA + ".* TO '" + READER_USER + "'@'%'");
            for (String sql : statements) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Migrates and grants exactly as the migrate role does. */
    public static synchronized void migrate() {
        try (var ds = (HikariDataSource) dataSource(MIGRATE_USER)) {
            SchemaVersion.migrate(ds);
            TableGrants.apply(ds);
        }
    }

    public static void freshlyMigratedSchema() {
        resetSchema();
        migrate();
    }
}
