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

	private PythonApi() {
	}

	static ServerProcess start(SuiteConfig config, Profile profile) {
		int port = config.basePort() + profile.portOffset();
		String database = config.databasePrefix() + "_" + profile.id().replace('-', '_');
		String jdbcUrl = recreateDatabase(config, database);
		URI pg = URI.create(jdbcUrl.substring("jdbc:".length()));
		String sqlalchemyUrl = "postgresql+psycopg://" + encode(config.postgresUser()) + ":"
				+ encode(config.postgresPassword()) + "@" + pg.getHost() + ":"
				+ (pg.getPort() < 0 ? 5432 : pg.getPort()) + "/" + database;

		Path dir = serversDir().resolve(profile.id());
		Path log = serversDir().resolve(profile.id() + ".log");
		try {
			Files.createDirectories(dir);
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		ProcessBuilder pb = new ProcessBuilder(
				List.of("uv", "run", "--no-sync", "--project", config.repoRoot().toString(), "archsim-api", "serve",
						"--host", "127.0.0.1", "--port", Integer.toString(port)))
			.directory(dir.toFile())
			.redirectErrorStream(true)
			.redirectOutput(log.toFile());
		Map<String, String> env = pb.environment();
		env.keySet().removeIf(k -> k.toUpperCase(Locale.ROOT).startsWith("ARCHSIM_"));
		env.put("PYTHONUNBUFFERED", "1");
		env.put("ARCHSIM_DATABASE_URL", sqlalchemyUrl);
		env.put("ARCHSIM_EXAMPLES_DIR", config.repoRoot().resolve("examples").toString());
		env.putAll(profile.settings(config));
		Process process;
		try {
			process = pb.start();
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot start the Python API (is uv on the PATH?)", e);
		}
		URI url = URI.create("http://127.0.0.1:" + port);
		ServerProcess server = new ServerProcess("the " + profile.id() + " Python API (" + url + ", log " + log + ")",
				process, url, jdbcUrl, log);
		server.awaitHealthy(Duration.ofMinutes(2));
		return server;
	}

	/** Where started servers run and log: {@code archsim.serversDir}, set by Gradle. */
	static Path serversDir() {
		String dir = System.getProperty("archsim.serversDir");
		return dir != null ? Path.of(dir) : Path.of(System.getProperty("java.io.tmpdir"), "archsim-api-servers");
	}

	/** Drops and creates {@code database}; returns its JDBC URL. */
	private static String recreateDatabase(SuiteConfig config, String database) {
		if (!database.matches("[a-z0-9_]+")) {
			throw new IllegalArgumentException("not a plain database name: " + database);
		}
		try (Connection c = DriverManager.getConnection(config.postgresUrl(), config.postgresUser(),
				config.postgresPassword()); Statement st = c.createStatement()) {
			st.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
			st.execute("CREATE DATABASE " + database);
		}
		catch (SQLException e) {
			throw new IllegalStateException("cannot create database " + database + " through " + config.postgresUrl()
					+ " (is PostgreSQL running? docker compose up -d postgres)", e);
		}
		URI admin = URI.create(config.postgresUrl().substring("jdbc:".length()));
		return "jdbc:postgresql://" + admin.getHost() + ":" + (admin.getPort() < 0 ? 5432 : admin.getPort()) + "/"
				+ database;
	}

	private static String encode(String s) {
		return URLEncoder.encode(s, StandardCharsets.UTF_8);
	}

}
