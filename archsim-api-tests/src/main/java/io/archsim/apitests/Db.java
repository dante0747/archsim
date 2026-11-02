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
 * SQL on a server's PostgreSQL database. The tables are part of the API's contract (the
 * Java API takes over the same tables), so tests use them where an HTTP client cannot: to
 * make a session expire, to make a sandbox workspace, or to check that only a hash is
 * stored.
 */
public final class Db {

	private final String url;

	private final String user;

	private final String password;

	/**
	 * Describes how to reach a database; each statement opens a connection of its own.
	 * @param url the database's JDBC URL
	 * @param user the user to connect as
	 * @param password the user's password
	 */
	public Db(String url, String user, String password) {
		this.url = url;
		this.user = user;
		this.password = password;
	}

	/**
	 * Runs an INSERT, UPDATE or DELETE.
	 * @param sql the statement, with a {@code ?} for each argument
	 * @param args the statement's arguments, in order
	 * @return how many rows it changed
	 */
	public int update(String sql, @Nullable Object... args) {
		try (Connection connection = connect(); PreparedStatement statement = prepare(connection, sql, args)) {
			return statement.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	/**
	 * Runs a query.
	 * @param sql the query, with a {@code ?} for each argument
	 * @param args the query's arguments, in order
	 * @return each row, a map from column name to value in the columns' order
	 */
	public List<Map<String, @Nullable Object>> rows(String sql, @Nullable Object... args) {
		try (Connection connection = connect();
				PreparedStatement statement = prepare(connection, sql, args);
				ResultSet resultSet = statement.executeQuery()) {
			return readRows(resultSet);
		}
		catch (SQLException ex) {
			throw new IllegalStateException(sql, ex);
		}
	}

	/**
	 * Runs a query and returns the first column of every row.
	 * @param sql the query, with a {@code ?} for each argument
	 * @param args the query's arguments, in order
	 * @return the first column's values, a row's after the row before
	 */
	public List<@Nullable Object> column(String sql, @Nullable Object... args) {
		List<@Nullable Object> values = new ArrayList<>();
		for (Map<String, @Nullable Object> row : rows(sql, args)) {
			values.add(row.values().iterator().next());
		}
		return values;
	}

	/**
	 * Runs a query and returns the first column of its first row.
	 * @param sql the query, with a {@code ?} for each argument
	 * @param args the query's arguments, in order
	 * @return the value, or null when there is no row
	 */
	public @Nullable Object value(String sql, @Nullable Object... args) {
		List<@Nullable Object> values = column(sql, args);
		return values.isEmpty() ? null : values.get(0);
	}

	private Connection connect() throws SQLException {
		return DriverManager.getConnection(this.url, this.user, this.password);
	}

	private static List<Map<String, @Nullable Object>> readRows(ResultSet resultSet) throws SQLException {
		ResultSetMetaData columns = resultSet.getMetaData();
		List<Map<String, @Nullable Object>> rows = new ArrayList<>();
		while (resultSet.next()) {
			Map<String, @Nullable Object> row = new LinkedHashMap<>();
			for (int i = 1; i <= columns.getColumnCount(); i++) {
				row.put(columns.getColumnLabel(i), resultSet.getObject(i));
			}
			rows.add(row);
		}
		return rows;
	}

	private static PreparedStatement prepare(Connection connection, String sql, @Nullable Object... args)
			throws SQLException {
		PreparedStatement statement = connection.prepareStatement(sql);
		for (int i = 0; i < args.length; i++) {
			statement.setObject(i + 1, args[i]);
		}
		return statement;
	}

}
