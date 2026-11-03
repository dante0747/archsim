package io.archsim.model.component;

/**
 * What the resource settings of parts (CPU, memory, storage, instance type) share: their
 * bounds, and the pieces their descriptions are built from, so each part's description
 * differs only in what the part is and what the setting does to it. Every one is
 * optional: left empty, it is not modelled.
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

	/**
	 * Starts a CPU setting's description, before what the part is: "vCPU per replica".
	 */
	public static final String CPU_PER = "vCPU per ";

	/** Starts a memory setting's description, before what the part is. */
	public static final String MEMORY_PER = "Memory per ";

	/** Starts a storage setting's description, before what the part is. */
	public static final String STORAGE_PER = "Disk per ";

	/** Follows the part in a memory or storage setting's description: the unit. */
	public static final String IN_GIB = " (GiB)";

	/** Follows the part (and unit) in a resource setting's description. */
	public static final String NOT_MODELLED_WHEN_EMPTY = ". Empty = not modelled. ";

	/** What a CPU setting does to a part whose work waits for cores. */
	public static final String CPU_CONTENTION = "When set, CPU work waits for a free core while more handlers than "
			+ "cores want the CPU (CPU contention); below 1 (e.g. 0.5) CPU work runs proportionally slower.";

	/** What a resource setting does to a part whose simulation does not use it. */
	public static final String PRICED_ONLY = "Shown on the design and used for price estimates; it does not "
			+ "change the simulation.";

	/** Starts an instance-type setting's description, before what the part is. */
	public static final String INSTANCE_TYPE_OF_EACH = "Instance type of each ";

	/** Ends an instance-type setting's description, after what the part is. */
	public static final String INSTANCE_TYPE_USE = " in the design's price snapshot (e.g. c6g.xlarge). It is used "
			+ "while it covers the CPU and memory; empty, or too small: the cheapest type that covers them. "
			+ "Auto-sizing records the type it chose in a run's input, so running that input again gives the same "
			+ "types and cost.";

	/** The description of a part's hyperthread sibling speed. */
	public static final String SIBLING_SPEED = "With 2 or more vCPU: the vCPUs are hyperthreads, two per physical "
			+ "core (as on most cloud instances), and a vCPU whose core's other vCPU is busy too runs at this "
			+ "speed, so a busy pair does twice this much work (about 0.6 on Intel and AMD cores: 1.2 cores' work). "
			+ "Empty: every vCPU is a full core (as before; right for Graviton and other cores without "
			+ "hyperthreads). Ignored when the design sets a host machine (architecture.host), whose sibling speed "
			+ "applies instead.";

	private Resources() {
	}

}
