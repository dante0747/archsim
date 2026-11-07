package io.archsim.engine.calibration.mapping;

import java.util.Arrays;
import java.util.Optional;

/**
 * A node key of a mapping that is not a setting: its source is fitted into settings
 * ({@code cpuPerRequest} into {@code cpuShare}; {@code memoryWorkingSet} into the memory
 * at idle, per request per second and per request held).
 */
public enum DerivedInput {

	/** CPU per request, fitted into {@code cpuShare}. */
	CPU_PER_REQUEST("cpuPerRequest", CpuPerRequestSource.class, "{cpu, requests}"),

	/** The working set, fitted into the memory settings. */
	MEMORY_WORKING_SET("memoryWorkingSet", WorkingSetSource.class, "{workingSet}");

	private final String key;

	private final Class<? extends NodeSource> source;

	private final String shape;

	DerivedInput(String key, Class<? extends NodeSource> source, String shape) {
		this.key = key;
		this.source = source;
		this.shape = shape;
	}

	/**
	 * The node key.
	 * @return the key, such as {@code cpuPerRequest}
	 */
	public String key() {
		return this.key;
	}

	/**
	 * The input a node key names.
	 * @param key a node key
	 * @return its input; empty for a key that names a setting
	 */
	public static Optional<DerivedInput> of(String key) {
		return Arrays.stream(values()).filter((input) -> input.key.equals(key)).findFirst();
	}

	/**
	 * The input whose source a source is.
	 * @param source a node's source
	 * @return the input; empty for a source of a setting
	 */
	static Optional<DerivedInput> ofSource(NodeSource source) {
		return Arrays.stream(values()).filter((input) -> input.source.isInstance(source)).findFirst();
	}

	/**
	 * Whether a source is of the kind this input needs.
	 * @param source a node's source
	 * @return {@code true} when it is
	 */
	boolean accepts(NodeSource source) {
		return this.source.isInstance(source);
	}

	/**
	 * The keys its source requires, as the Python engine names them in a refusal.
	 * @return the keys, such as {@code {cpu, requests}}
	 */
	String shape() {
		return this.shape;
	}

}
