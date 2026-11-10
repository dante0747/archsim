package io.archsim.apitests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An API the suite can start ({@code archsim.api.start}), and the commands that serve it
 * on the loopback interface, with a worker next to it.
 */
public enum StartableApi {

	/**
	 * The Java API ({@code java -jar archsim-app.jar api}), on the JDK the suite runs on,
	 * and the Java worker that simulates the runs it queues
	 * ({@code archsim-app.jar worker}, ADR-0053). Gradle builds the jar first when
	 * {@code archsim.api.start=java}.
	 */
	JAVA;

	/** How many runs a worker the suite starts takes at once, unless its profile says. */
	static final int WORKER_RUNS = 3;

	/** The heap archsim-app's README gives each run a worker takes at once, in MB. */
	private static final int RUN_HEAP_MB = 512;

	/** The heap it gives the application besides, in MB. */
	private static final int APP_HEAP_MB = 256;

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
		return List.of(java(), "-jar", jar(config).toString(), "api", "--server.address=" + LOOPBACK,
				"--server.port=" + port);
	}

	/**
	 * Returns the command that runs the Java worker: a heap for the runs it takes at
	 * once, by the rule of archsim-app's README, and an exit when the heap runs out, so
	 * its runs are requeued (ADR-0053).
	 * @param config what the suite was told: the repository
	 * @param runs how many runs it takes at once ({@code ARCHSIM_MAX_CONCURRENT_RUNS})
	 * @return the command and its arguments
	 * @throws IllegalStateException when the API's jar is not built
	 */
	static List<String> workerCommand(SuiteConfig config, int runs) {
		return List.of(java(), "-Xmx" + (RUN_HEAP_MB * runs + APP_HEAP_MB) + "m", "-XX:+ExitOnOutOfMemoryError", "-jar",
				jar(config).toString(), "worker");
	}

	private static Path jar(SuiteConfig config) {
		Path jar = config.repoRoot().resolve(APP_JAR);
		if (!Files.isRegularFile(jar)) {
			throw new IllegalStateException("no " + jar + ": build it with ./gradlew :archsim-app:bootJar");
		}
		return jar;
	}

	/**
	 * Returns the JDK's {@code java}, the one the suite runs on.
	 * @return its path
	 */
	private static String java() {
		return Path.of(System.getProperty("java.home"), "bin", "java").toString();
	}

	private String id() {
		return name().toLowerCase(Locale.ROOT);
	}

}
