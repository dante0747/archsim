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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Starts an API ({@link StartableApi}) as a server of a profile, on a PostgreSQL database
 * of its own, created afresh. It runs in a directory of its own under
 * {@code build/api-servers/}, so a {@code .env} file in the repository does not reach it,
 * and none of the caller's {@code ARCHSIM_*} variables do either: it gets its profile's
 * settings, in the environment either API reads.
 */
final class LocalApi {

	/** The setting the engine service reads: more folders of price snapshots. */
	private static final String PRICES_DIR = "ARCHSIM_PRICES_DIR";

	/** How far above an API's port the engine service next to it listens. */
	private static final int ENGINE_PORT_OFFSET = 500;

	/** The port PostgreSQL listens on when a JDBC URL names none. */
	private static final int DEFAULT_POSTGRES_PORT = 5432;

	/** What a JDBC URL starts with, before the database's own URL. */
	private static final String JDBC_PREFIX = "jdbc:";

	private LocalApi() {
	}

	/**
	 * Starts an API as a server of a profile, and the Python engine service before it
	 * when the API needs one, and waits until they are healthy.
	 * @param config what the suite was told: ports, PostgreSQL, the repository
	 * @param profile the profile to start a server of
	 * @param api the API to start
	 * @return the running API, and every process started, to stop when the run ends
	 */
	static Started start(SuiteConfig config, Profile profile, StartableApi api) {
		int port = config.basePort() + profile.portOffset();
		String database = config.databasePrefix() + "_" + profile.id().replace('-', '_');
		String jdbcUrl = recreateDatabase(config, database);
		Map<String, String> settings = new HashMap<>(profile.settings(config));
		settings.put("ARCHSIM_DATABASE_URL", sqlAlchemyUrl(config, jdbcUrl, database));
		settings.put("ARCHSIM_EXAMPLES_DIR", config.repoRoot().resolve("examples").toString());
		List<ServerProcess> started = new ArrayList<>();
		try {
			if (api.needsEngineService()) {
				URI engine = URI.create("http://127.0.0.1:" + (port + ENGINE_PORT_OFFSET));
				started.add(launchEngineService(config, profile,
						new ServerProcess.Urls(engine, jdbcUrl, engine.resolve("/health")), settings));
				settings.put("ARCHSIM_ENGINE_URL", engine.toString());
			}
			URI url = URI.create("http://127.0.0.1:" + port);
			ServerProcess server = launch(
					new Launch(profile.id(), "the " + profile.id() + " " + api.label() + " API (" + url + ")",
							api.command(config, port),
							new ServerProcess.Urls(url, jdbcUrl, url.resolve(ApiClient.API + "/health"))),
					settings, profile);
			started.add(server);
			return new Started(server, started);
		}
		catch (IllegalStateException | UncheckedIOException ex) {
			// what did start would hold its port into the next run
			started.forEach(ServerProcess::stop);
			throw ex;
		}
	}

	/**
	 * Starts the Python engine service for an API, with the one setting the engine reads:
	 * the extra price snapshots ({@code ARCHSIM_PRICES_DIR}).
	 * @param config what the suite was told: the repository
	 * @param profile the profile the API serves
	 * @param urls where the service listens and how to tell it is ready
	 * @param settings the API's {@code ARCHSIM_*} settings
	 * @return the running service
	 */
	private static ServerProcess launchEngineService(SuiteConfig config, Profile profile, ServerProcess.Urls urls,
			Map<String, String> settings) {
		Map<String, String> engineSettings = new HashMap<>();
		if (settings.containsKey(PRICES_DIR)) {
			engineSettings.put(PRICES_DIR, settings.get(PRICES_DIR));
		}
		return launch(
				new Launch(profile.id() + "-engine", "the " + profile.id() + " engine service (" + urls.api() + ")",
						StartableApi.engineServiceCommand(config, urls.api().getPort()), urls),
				engineSettings, profile);
	}

	/**
	 * Starts a process with only the given {@code ARCHSIM_*} settings and waits until it
	 * is healthy.
	 * @param launch what to start
	 * @param settings its {@code ARCHSIM_*} environment
	 * @param profile the profile it serves, whose directory it runs in
	 * @return the running process
	 */
	private static ServerProcess launch(Launch launch, Map<String, String> settings, Profile profile) {
		Path logFile = serversDir().resolve(launch.log() + ".log");
		ProcessBuilder builder = new ProcessBuilder(launch.command()).directory(workingDirectory(profile).toFile())
			.redirectErrorStream(true)
			.redirectOutput(logFile.toFile());
		Map<String, String> env = builder.environment();
		env.keySet().removeIf((variable) -> variable.toUpperCase(Locale.ROOT).startsWith("ARCHSIM_"));
		env.put("PYTHONUNBUFFERED", "1");
		env.putAll(settings);
		String described = launch.name() + ", log " + logFile;
		ServerProcess server = new ServerProcess(described, run(builder, described), launch.urls(), logFile);
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

	private static Process run(ProcessBuilder command, String name) {
		try {
			return command.start();
		}
		catch (IOException ex) {
			throw new UncheckedIOException("cannot start " + name + " with " + command.command().getFirst(), ex);
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
	 * Returns the URL the API reaches its database at, as the Python API takes it; the
	 * Java API reads the same.
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

	/**
	 * What {@link #start} started.
	 *
	 * @param api the API's server
	 * @param processes every process started, the API's included, to stop at the end
	 */
	record Started(ServerProcess api, List<ServerProcess> processes) {

	}

	/**
	 * A server to start.
	 *
	 * @param log its log's name, under {@code build/api-servers/}
	 * @param name the server as messages name it
	 * @param command the command that serves it
	 * @param urls where it answers, where its database is and how to tell it is ready
	 */
	private record Launch(String log, String name, List<String> command, ServerProcess.Urls urls) {

	}

}
