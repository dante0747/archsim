package io.archsim.apitests;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starts today's Python API ({@code uv run archsim-api serve}) as a server of a profile,
 * on a PostgreSQL database of its own, created afresh. It runs in a directory of its own
 * under {@code build/api-servers/}, so a {@code .env} file in the repository does not
 * reach it, and none of the caller's {@code ARCHSIM_*} variables do either. Needs
 * {@code uv} on the PATH and a synced environment ({@code uv sync}).
 */
final class PythonApi {

	/** The port PostgreSQL listens on when a JDBC URL names none. */
	private static final int DEFAULT_POSTGRES_PORT = 5432;

	/** What a JDBC URL starts with, before the database's own URL. */
	private static final String JDBC_PREFIX = "jdbc:";

	private PythonApi() {
	}

	/**
	 * Starts the Python API as a server of a profile, and waits until it is healthy.
	 * @param config what the suite was told: ports, PostgreSQL, the repository
	 * @param profile the profile to start a server of
	 * @return the running server, to stop when the run ends
	 */
	static ServerProcess start(SuiteConfig config, Profile profile) {
		int port = config.basePort() + profile.portOffset();
		String database = config.databasePrefix() + "_" + profile.id().replace('-', '_');
		String jdbcUrl = recreateDatabase(config, database);
		Path log = serversDir().resolve(profile.id() + ".log");
		ProcessBuilder command = serveCommand(config, port, workingDirectory(profile), log);
		Map<String, String> env = command.environment();
		env.keySet().removeIf((name) -> name.toUpperCase(Locale.ROOT).startsWith("ARCHSIM_"));
		env.put("PYTHONUNBUFFERED", "1");
		env.put("ARCHSIM_DATABASE_URL", sqlAlchemyUrl(config, jdbcUrl, database));
		env.put("ARCHSIM_EXAMPLES_DIR", config.repoRoot().resolve("examples").toString());
		env.putAll(profile.settings(config));
		URI url = URI.create("http://127.0.0.1:" + port);
		ServerProcess server = new ServerProcess("the " + profile.id() + " Python API (" + url + ", log " + log + ")",
				launch(command), new ServerProcess.Urls(url, jdbcUrl), log);
		server.awaitHealthy(Duration.ofMinutes(2));
		return server;
	}

	/**
	 * Returns where started servers run and log: {@code archsim.serversDir}, set by
	 * Gradle.
	 * @return the directory, or one under the system's temporary directory when Gradle
	 * did not name one
	 */
	static Path serversDir() {
		String dir = System.getProperty("archsim.serversDir");
		return (dir != null) ? Path.of(dir) : Path.of(System.getProperty("java.io.tmpdir"), "archsim-api-servers");
	}

	private static Path workingDirectory(Profile profile) {
		Path dir = serversDir().resolve(profile.id());
		try {
			Files.createDirectories(dir);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return dir;
	}

	private static ProcessBuilder serveCommand(SuiteConfig config, int port, Path directory, Path log) {
		return new ProcessBuilder(List.of("uv", "run", "--no-sync", "--project", config.repoRoot().toString(),
				"archsim-api", "serve", "--host", "127.0.0.1", "--port", Integer.toString(port)))
			.directory(directory.toFile())
			.redirectErrorStream(true)
			.redirectOutput(log.toFile());
	}

	private static Process launch(ProcessBuilder command) {
		try {
			return command.start();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot start the Python API (is uv on the PATH?)", ex);
		}
	}

	/**
	 * Drops and creates a database.
	 * @param config what the suite was told: the PostgreSQL server and its user
	 * @param database the database's name, made of lower-case letters, digits and
	 * underscores
	 * @return the database's JDBC URL
	 */
	private static String recreateDatabase(SuiteConfig config, String database) {
		if (!database.matches("[a-z0-9_]+")) {
			throw new IllegalArgumentException("not a plain database name: " + database);
		}
		try (Connection connection = DriverManager.getConnection(config.postgresUrl(), config.postgresUser(),
				config.postgresPassword()); Statement statement = connection.createStatement()) {
			statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
			statement.execute("CREATE DATABASE " + database);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("cannot create database " + database + " through " + config.postgresUrl()
					+ " (is PostgreSQL running? docker compose up -d postgres)", ex);
		}
		URI admin = URI.create(config.postgresUrl().substring(JDBC_PREFIX.length()));
		return "jdbc:postgresql://" + admin.getHost() + ":" + portOf(admin) + "/" + database;
	}

	/**
	 * Returns the URL the Python API reaches its database at.
	 * @param config what the suite was told: the PostgreSQL user
	 * @param jdbcUrl the database's JDBC URL
	 * @param database the database's name
	 * @return the database's SQLAlchemy URL, with the user and password in it
	 */
	private static String sqlAlchemyUrl(SuiteConfig config, String jdbcUrl, String database) {
		URI postgres = URI.create(jdbcUrl.substring(JDBC_PREFIX.length()));
		return "postgresql+psycopg://" + encode(config.postgresUser()) + ":" + encode(config.postgresPassword()) + "@"
				+ postgres.getHost() + ":" + portOf(postgres) + "/" + database;
	}

	private static int portOf(URI postgres) {
		return (postgres.getPort() < 0) ? DEFAULT_POSTGRES_PORT : postgres.getPort();
	}

	private static String encode(String text) {
		return URLEncoder.encode(text, StandardCharsets.UTF_8);
	}

}
