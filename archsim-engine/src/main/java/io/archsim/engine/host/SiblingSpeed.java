package io.archsim.engine.host;

/**
 * How a part's vCPUs count: the speed of a vCPU whose core's other vCPU is busy too, and
 * why.
 *
 * @param speed the sibling speed, above 0 and at most 1; 1 for whole cores
 * @param basis why its vCPUs count so
 */
public record SiblingSpeed(double speed, VcpuBasis basis) {

	/** The sibling speed of vCPUs that are whole cores. */
	public static final double WHOLE_CORES = 1.0;

	/**
	 * Whether its vCPUs are hyperthread pairs, which slow each other when both are busy.
	 * @return {@code true} for a speed below 1
	 */
	public boolean pairs() {
		return this.speed < WHOLE_CORES;
	}

}
