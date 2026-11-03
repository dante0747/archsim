package io.archsim.apitests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An API the suite can start ({@code archsim.api.start}), and the commands that serve it
 * on the loopback interface, with the Python engine service and worker next to it.
 */
public enum StartableApi {

	/**
	 * The Java API ({@code java -jar archsim-app.jar api}), on the JDK the suite runs on.
	 * Gradle builds the jar first when {@code archsim.api.start=java}. The Python engine
	 * service answers its engine calls (ADR-0036), and a Python worker simulates the runs
	 * it queues, until stage 4 of the port. Both need {@code uv} on the PATH and a synced
	 * environment ({@code uv sync}).
	 */
	JAVA;

	/** Where a started API listens: only this machine can reach it. */
	private static final String LOOPBACK = "127.0.0.1";

	/** archsim-app's runnable jar, relative to the repository. */
	private static final String APP_JAR = "archsim-app/build/libs/archsim-app.jar";

	/**
	 * The API a value of {@code archsim.api.start} names.
	 * @param name {@code java}, in any case
	 * @return the API
	 * @throws IllegalArgumentException when it names no API the suite can start
	 */
	public static StartableApi of(String name) {
		return Arrays.stream(values())
			.filter((api) -> api.name().equalsIgnoreCase(name.strip()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("archsim.api.start=" + name + ": the suite can start "
					+ Arrays.stream(values()).map(StartableApi::id).toList()));
	}

	/**
	 * Returns the command that serves the API.
	 * @param config what the suite was told: the repository
	 * @param port the port to listen on
	 * @return the command and its arguments
	 * @throws IllegalStateException when the API's jar is not built
	 */
	List<String> command(SuiteConfig config, int port) {
		Path jar = config.repoRoot().resolve(APP_JAR);
		if (!Files.isRegularFile(jar)) {
			throw new IllegalStateException("no " + jar + ": build it with ./gradlew :archsim-app:bootJar");
		}
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		return List.of(java.toString(), "-jar", jar.toString(), "api", "--server.address=" + LOOPBACK,
				"--server.port=" + port);
	}

	/**
	 * Returns the command that runs a Python worker.
	 * @param config what the suite was told: the repository
	 * @return the command and its arguments
	 */
	static List<String> workerCommand(SuiteConfig config) {
		return List.of("uv", "run", "--no-sync", "--project", config.repoRoot().toString(), "archsim-api", "worker");
	}

	/**
	 * Returns the command that serves the Python engine service.
	 * @param config what the suite was told: the repository
	 * @param port the port to listen on
	 * @return the command and its arguments
	 */
	static List<String> engineServiceCommand(SuiteConfig config, int port) {
		return List.of("uv", "run", "--no-sync", "--project", config.repoRoot().toString(), "archsim-engine-service",
				"--host", LOOPBACK, "--port", Integer.toString(port));
	}

	private String id() {
		return name().toLowerCase(Locale.ROOT);
	}

}
