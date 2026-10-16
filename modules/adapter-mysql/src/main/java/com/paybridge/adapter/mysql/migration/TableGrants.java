package com.paybridge.adapter.mysql.migration;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;

/** Applies db/grants/table-grants.sql with the DDL account. Idempotent. */
public final class TableGrants {
    static final String RESOURCE = "db/grants/table-grants.sql";

    private TableGrants() {}

    public static List<String> statements() {
        try (var in = new ClassPathResource(RESOURCE).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("--"))
                    .map(l -> l.endsWith(";") ? l.substring(0, l.length() - 1) : l)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Returns the number of statements executed. */
    public static int apply(DataSource ddlDataSource) {
        var statements = statements();
        try (var con = ddlDataSource.getConnection(); var st = con.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("applying table grants failed: " + e.getMessage(), e);
        }
        return statements.size();
    }
}
