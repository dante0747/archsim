package io.archsim.engine.export;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * How the exports deploy a technology: its container image and port, or a note that it is
 * a managed service nothing deploys.
 *
 * @param image the container image; empty for a managed service
 * @param port the port it listens on
 * @param managed whether it is a managed service, which callers reach by its address
 * @param environment the container's environment, in order
 * @param command the container's arguments
 * @param note what a reader of the export should know about it; {@code null} for nothing
 * @param dataPath where its data lives in the container; {@code null} for {@code /data}
 */
record TechnologySpec(String image, int port, boolean managed, Map<String, String> environment, List<String> command,
		@Nullable String note, @Nullable String dataPath) {

	/** Where a store keeps its data when its technology names no path. */
	private static final String DEFAULT_DATA_PATH = "/data";

	/**
	 * Copies the environment and the command, so the record does not change.
	 */
	TechnologySpec {
		environment = Collections.unmodifiableMap(new LinkedHashMap<>(environment));
		command = List.copyOf(command);
	}

	/**
	 * An image to run.
	 * @param image the container image
	 * @param port the port it listens on
	 * @return the spec, with no environment, command, note or data path
	 */
	static TechnologySpec image(String image, int port) {
		return new TechnologySpec(image, port, false, Map.of(), List.of(), null, null);
	}

	/**
	 * A managed service nothing deploys.
	 * @param port the port callers reach it on
	 * @param note what it is
	 * @return the spec
	 */
	static TechnologySpec managed(int port, String note) {
		return new TechnologySpec("", port, true, Map.of(), List.of(), note, null);
	}

	/**
	 * This spec with its data in a path.
	 * @param path where its data lives in the container
	 * @return the spec
	 */
	TechnologySpec storingIn(String path) {
		return new TechnologySpec(this.image, this.port, this.managed, this.environment, this.command, this.note, path);
	}

	/**
	 * This spec with an environment.
	 * @param variables the variables, names and values in turn
	 * @return the spec
	 */
	TechnologySpec withEnvironment(String... variables) {
		Map<String, String> byName = new LinkedHashMap<>();
		for (int i = 0; i < variables.length; i += 2) {
			byName.put(variables[i], variables[i + 1]);
		}
		return new TechnologySpec(this.image, this.port, this.managed, byName, this.command, this.note, this.dataPath);
	}

	/**
	 * This spec with arguments.
	 * @param arguments the container's arguments
	 * @return the spec
	 */
	TechnologySpec withCommand(String... arguments) {
		return new TechnologySpec(this.image, this.port, this.managed, this.environment, List.of(arguments), this.note,
				this.dataPath);
	}

	/**
	 * This spec with a note.
	 * @param text what a reader of the export should know
	 * @return the spec
	 */
	TechnologySpec noting(String text) {
		return new TechnologySpec(this.image, this.port, this.managed, this.environment, this.command, text,
				this.dataPath);
	}

	/**
	 * What a reader of the export should know about it.
	 * @return the note; empty for none
	 */
	Optional<String> noteText() {
		return Optional.ofNullable(this.note);
	}

	/**
	 * Where its data lives in the container.
	 * @return the path
	 */
	String dataDirectory() {
		return (this.dataPath != null) ? this.dataPath : DEFAULT_DATA_PATH;
	}

}
