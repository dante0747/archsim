package io.archsim.app.database;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres;
import org.flywaydb.core.Flyway;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * PostgreSQL for the tests: the real server, from binaries Gradle fetches from Maven
 * Central, started once per test JVM, with no Docker (ADR-0033). Each caller gets a
 * database of its own, so tests never share rows.
 */
public final class TestDatabases {

	private static final AtomicInteger NEXT = new AtomicInteger();

	private TestDatabases() {
	}

	/**
	 * A new, empty database.
	 * @return its JDBC URL, user included
	 */
	public static String freshJdbcUrl() {
		return create("archsim_test_" + NEXT.incrementAndGet(), null);
	}

	/**
	 * A new database at the latest schema, as the {@code api} or {@code migrate} role
	 * leaves it: a copy of one Flyway migrated once, which takes a fraction of the time.
	 * @return its JDBC URL, user included
	 */
	public static String migratedJdbcUrl() {
		return create("archsim_test_" + NEXT.incrementAndGet(), Migrated.TEMPLATE);
	}

	// a database, empty or a copy of a template
	private static String create(String name, @Nullable String template) {
		try (Connection admin = Server.INSTANCE.getPostgresDatabase().getConnection();
				Statement statement = admin.createStatement()) {
			statement.execute("CREATE DATABASE " + name + ((template != null) ? " TEMPLATE " + template : ""));
		}
		catch (SQLException ex) {
			throw new IllegalStateException("cannot create the test database " + name, ex);
		}
		return Server.INSTANCE.getJdbcUrl("postgres", name);
	}

	/** The database migrated ones copy, made when a test first needs one. */
	private static final class Migrated {

		private static final String TEMPLATE = migrate();

		private static String migrate() {
			String name = "archsim_migrated_template";
			String url = create(name, null);
			Flyway.configure().dataSource(new DriverManagerDataSource(url)).load().migrate();
			return name;
		}

	}

	/** The server, started when a test first needs it and stopped when the JVM exits. */
	private static final class Server {

		private static final EmbeddedPostgres INSTANCE = start();

		private static EmbeddedPostgres start() {
			try {
				EmbeddedPostgres server = EmbeddedPostgres.builder().start();
				Runtime.getRuntime().addShutdownHook(new Thread(() -> close(server)));
				return server;
			}
			catch (IOException ex) {
				throw new UncheckedIOException("cannot start the embedded PostgreSQL", ex);
			}
		}

		private static void close(EmbeddedPostgres server) {
			try {
				server.close();
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

	}

}
