package io.archsim.apitests;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * SQL on a server's PostgreSQL database. The tables are part of the API's contract (the Java
 * API takes over the same tables), so tests use them where an HTTP client cannot: to make a
 * session expire, to make a sandbox workspace, or to check that only a hash is stored.
 */
public final class Db {
    private final String url;
    private final String user;
    private final String password;

    public Db(String url, String user, String password) {
        this.url = url;
        this.user = user;
        this.password = password;
    }

    /** Runs an INSERT, UPDATE or DELETE; returns the rows it changed. */
    public int update(String sql, @Nullable Object... args) {
        try (Connection c = connect();
                PreparedStatement st = prepare(c, sql, args)) {
            return st.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** The rows of a query, each a map from column name to value. */
    public List<Map<String, @Nullable Object>> rows(String sql, @Nullable Object... args) {
        try (Connection c = connect();
                PreparedStatement st = prepare(c, sql, args);
                ResultSet rs = st.executeQuery()) {
            ResultSetMetaData meta = rs.getMetaData();
            List<Map<String, @Nullable Object>> out = new ArrayList<>();
            while (rs.next()) {
                Map<String, @Nullable Object> row = new LinkedHashMap<>();
                for (int i = 1; i <= meta.getColumnCount(); i++) {
                    row.put(meta.getColumnLabel(i), rs.getObject(i));
                }
                out.add(row);
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException(sql, e);
        }
    }

    /** The first column of every row. */
    public List<@Nullable Object> column(String sql, @Nullable Object... args) {
        List<@Nullable Object> out = new ArrayList<>();
        for (Map<String, @Nullable Object> row : rows(sql, args)) {
            out.add(row.values().iterator().next());
        }
        return out;
    }

    /** The first column of the first row, or null when there is no row. */
    public @Nullable Object value(String sql, @Nullable Object... args) {
        List<@Nullable Object> values = column(sql, args);
        return values.isEmpty() ? null : values.get(0);
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(url, user, password);
    }

    private static PreparedStatement prepare(Connection c, String sql, @Nullable Object... args) throws SQLException {
        PreparedStatement st = c.prepareStatement(sql);
        for (int i = 0; i < args.length; i++) {
            st.setObject(i + 1, args[i]);
        }
        return st;
    }
}
