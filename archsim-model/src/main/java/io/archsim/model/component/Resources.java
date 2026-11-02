package io.archsim.model.component;

/**
 * What the resource settings of parts (CPU, memory, storage, instance type) share. Every
 * one is optional: left empty, it is not modelled.
 */
public final class Resources {

	/** An instance type in a price snapshot, such as {@code c6g.xlarge}. */
	public static final String INSTANCE_TYPE = "^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$";

	/** The most vCPU a part's instance may have. */
	public static final double MAX_CPU_CORES = 512;

	/** The most memory a part's instance may have, in GiB. */
	public static final double MAX_MEMORY_GIB = 24_576;

	/** The most disk a part's instance may have, in GiB. */
	public static final double MAX_STORAGE_GIB = 1_000_000;

	private Resources() {
	}

}
