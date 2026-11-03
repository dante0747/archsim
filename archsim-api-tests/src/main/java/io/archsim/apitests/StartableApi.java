package io.archsim.apitests;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * An API the suite can start ({@code archsim.api.start}), and the command that serves it
 * on a port of the loopback interface.
 */
public enum StartableApi {

	/**
	 * Today's Python API ({@code uv run archsim-api serve}). Needs {@code uv} on the PATH
	 * and a synced environment ({@code uv sync}).
	 */
	PYTHON("Python") {

		@Override
		List<String> command(SuiteConfig config, int port) {
			return List.of("uv", "run", "--no-sync", "--project", config.repoRoot().toString(), "archsim-api", "serve",
					"--host", LOOPBACK, "--port", Integer.toString(port));
		}

	},

	/**
	 * The Java API ({@code java -jar archsim-app.jar api}), on the JDK the suite runs on.
	 * Gradle builds the jar first when {@code archsim.api.start=java}.
	 */
	JAVA("Java") {

		@Override
		List<String> command(SuiteConfig config, int port) {
			Path jar = config.repoRoot().resolve(APP_JAR);
			if (!Files.isRegularFile(jar)) {
				throw new IllegalStateException("no " + jar + ": build it with ./gradlew :archsim-app:bootJar");
			}
			Path java = Path.of(System.getProperty("java.home"), "bin", "java");
			return List.of(java.toString(), "-jar", jar.toString(), "api", "--server.address=" + LOOPBACK,
					"--server.port=" + port);
		}

	};

	/** Where a started API listens: only this machine can reach it. */
	private static final String LOOPBACK = "127.0.0.1";

	/** archsim-app's runnable jar, relative to the repository. */
	private static final String APP_JAR = "archsim-app/build/libs/archsim-app.jar";

	private final String label;

	StartableApi(String label) {
		this.label = label;
	}

	/**
	 * The API a value of {@code archsim.api.start} names.
	 * @param name {@code python} or {@code java}, in any case
	 * @return the API
	 * @throws IllegalArgumentException when the name is neither
	 */
	public static StartableApi of(String name) {
		return Arrays.stream(values())
			.filter((api) -> api.name().equalsIgnoreCase(name.strip()))
			.findFirst()
			.orElseThrow(() -> new IllegalArgumentException("archsim.api.start=" + name + ": the suite can start "
					+ Arrays.stream(values()).map(StartableApi::id).toList()));
	}

	/**
	 * Returns the API's name for people.
	 * @return {@code Python} or {@code Java}
	 */
	public String label() {
		return this.label;
	}

	/**
	 * Returns the command that serves the API.
	 * @param config what the suite was told: the repository
	 * @param port the port to listen on
	 * @return the command and its arguments
	 */
	abstract List<String> command(SuiteConfig config, int port);

	private String id() {
		return name().toLowerCase(Locale.ROOT);
	}

}
