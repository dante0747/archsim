package io.archsim.engine.sizing;

/** A resource spec a part may have, and auto-sizing fills when it is empty. */
public enum Spec {

	/** The vCPU of each instance. */
	CPU_CORES("cpuCores"),

	/** The memory of each instance, in GiB. */
	MEMORY_GIB("memoryGib"),

	/** The disk of each instance, in GiB. */
	STORAGE_GIB("storageGib");

	private final String json;

	Spec(String json) {
		this.json = json;
	}

	/**
	 * The spec's name in a node's config.
	 * @return {@code cpuCores}, {@code memoryGib} or {@code storageGib}
	 */
	public String json() {
		return this.json;
	}

}
