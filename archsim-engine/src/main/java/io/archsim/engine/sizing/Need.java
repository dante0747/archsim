package io.archsim.engine.sizing;

/** What a part needs, before its size is rounded to an instance. */
public enum Need {

	/** The CPU cores busy at the peak. */
	BUSY_CORES("busyCores", Spec.CPU_CORES),

	/** The cores the busy ones need at the target utilization. */
	CPU_CORES("cpuCores", Spec.CPU_CORES),

	/** The memory it uses. */
	MEMORY_GIB("memoryGib", Spec.MEMORY_GIB),

	/** The most memory a service holds, when every queue is full. */
	MEMORY_GIB_MAX("memoryGibMax", Spec.MEMORY_GIB),

	/** The data it stores. */
	STORAGE_GIB("storageGib", Spec.STORAGE_GIB),

	/**
	 * The CPU cores busy at the steady base load, added last as the Python engine adds
	 * it.
	 */
	BASE_BUSY_CORES("baseBusyCores", Spec.CPU_CORES);

	private final String json;

	private final Spec spec;

	Need(String json, Spec spec) {
		this.json = json;
		this.spec = spec;
	}

	/**
	 * The need's name in JSON.
	 * @return its camelCase name
	 */
	public String json() {
		return this.json;
	}

	/**
	 * The spec the need is about.
	 * @return its spec
	 */
	public Spec spec() {
		return this.spec;
	}

}
