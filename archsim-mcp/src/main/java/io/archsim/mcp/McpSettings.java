package io.archsim.mcp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.simulation.SimulationLimits;

/**
 * What the server serves from and how, as the Python server read it from its environment:
 * the templates' folder ({@code ARCHSIM_EXAMPLES_DIR}, else the first {@code examples}
 * folder of JSON files above the server's own files, as in a checkout), and each run's
 * limits ({@code ARCHSIM_MCP_MAX_REQUESTS}, {@code ARCHSIM_MCP_MAX_WALL_SECONDS}); and
 * the clock a calibration stamps its time from.
 *
 * @param examples the templates' folder; {@code null} for none
 * @param limits the limits each run is held to
 * @param clock what tells a calibration the time
 */
record McpSettings(@Nullable Path examples, SimulationLimits limits, Clock clock) {

	/** The requests a run may make unless the environment says: the Python server's. */
	static final long MAX_REQUESTS = 2_000_000;

	/** The seconds a run may take unless the environment says: the Python server's. */
	static final double MAX_WALL_SECONDS = 120;

	/**
	 * The settings an environment gives.
	 * @param environment the environment's variables
	 * @param installed where the server's own files are
	 * @return the settings
	 * @throws NumberFormatException when a limit is not a number
	 */
	static McpSettings of(Map<String, String> environment, Path installed) {
		String examples = environment.get("ARCHSIM_EXAMPLES_DIR");
		SimulationLimits limits = new SimulationLimits(
				limit(environment, "ARCHSIM_MCP_MAX_REQUESTS", Long::parseLong, MAX_REQUESTS),
				limit(environment, "ARCHSIM_MCP_MAX_WALL_SECONDS", Double::parseDouble, MAX_WALL_SECONDS));
		return new McpSettings((examples != null && !examples.isEmpty()) ? Path.of(examples) : found(installed), limits,
				Clock.systemUTC());
	}

	/**
	 * The page on what the simulation models: {@code docs/simulation-model.md} beside the
	 * templates' folder.
	 * @return the page; {@code null} without a templates' folder
	 */
	@Nullable Path simulationModel() {
		Path parent = (this.examples != null) ? this.examples.toAbsolutePath().getParent() : null;
		return (parent != null) ? parent.resolve("docs").resolve("simulation-model.md") : null;
	}

	// a limit the environment sets, read as Python's int() and float() read it, the
	// spaces around it included
	private static <T> T limit(Map<String, String> environment, String name, Function<String, T> parse, T otherwise) {
		String value = environment.get(name);
		return (value != null) ? parse.apply(value.strip()) : otherwise;
	}

	// the first examples folder holding JSON above the server's files
	private static @Nullable Path found(Path installed) {
		for (Path folder = installed.toAbsolutePath(); folder != null; folder = folder.getParent()) {
			Path candidate = folder.resolve("examples");
			if (holdsJson(candidate)) {
				return candidate;
			}
		}
		return null;
	}

	private static boolean holdsJson(Path folder) {
		if (!Files.isDirectory(folder)) {
			return false;
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.anyMatch((file) -> file.getFileName().toString().endsWith(".json"));
		}
		catch (IOException ex) {
			return false;
		}
	}

}
