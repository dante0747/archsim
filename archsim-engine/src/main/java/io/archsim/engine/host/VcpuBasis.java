package io.archsim.engine.host;

/**
 * Why a part's vCPUs count as they do (schema 1.11, ADR-0091): the first of these that
 * applies to a service, database or cache with 2 or more vCPU, in a design without a host
 * machine.
 */
public enum VcpuBasis {

	/** The part sets its {@code siblingSpeed}. */
	PART("part", "set on the part"),

	/** The design sets {@code architecture.siblingSpeed}. */
	DESIGN("design", "set on the design (architecture.siblingSpeed)"),

	/**
	 * The instance type the cost prices for the part is of a family whose vCPUs are whole
	 * cores.
	 */
	WHOLE_CORE_FAMILY("wholeCoreFamily", "its instance family's vCPUs are whole cores"),

	/** The service runs serverless, where the provider's vCPU is not a pair's thread. */
	SERVERLESS("serverless", "it runs serverless"),

	/**
	 * The service runs as pods on Kubernetes, which share their node's cores with other
	 * pods: counted as whole cores until the node is the machine.
	 */
	POD("pod", "it runs as pods on Kubernetes, which is optimistic on an x86 node pool"),

	/** Nothing else says: hyperthread pairs, as on most x86 instances. */
	DEFAULT("default", "the default, as on most x86 instances");

	private final String json;

	private final String words;

	VcpuBasis(String json, String words) {
		this.json = json;
		this.words = words;
	}

	/**
	 * The basis's name in JSON.
	 * @return its camelCase name
	 */
	public String json() {
		return this.json;
	}

	/**
	 * The basis in words, as a part's summary gives it.
	 * @return such as "set on the part"
	 */
	public String words() {
		return this.words;
	}

	/**
	 * Whether the engine inferred the speed, rather than read it from the design: from
	 * the part's instance family, its platform, or the default.
	 * @return {@code true} unless the part or the design sets it
	 */
	public boolean inferred() {
		return this != PART && this != DESIGN;
	}

}
