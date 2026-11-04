package io.archsim.engine.pricing;

import org.jspecify.annotations.Nullable;

/**
 * The size an instance type must cover.
 *
 * @param cpu the vCPU it needs, or {@code null} for any
 * @param memoryGib the memory it needs, in GiB, or {@code null} for any
 * @param busy the cores one instance keeps busy, for the credit check of a burstable
 * type; {@code null} when unknown (no burstable type then)
 */
public record MachineSize(@Nullable Double cpu, @Nullable Double memoryGib, @Nullable Busy busy) {

	private static final double TOLERANCE = 1e-9;

	/**
	 * Whether a machine of this many vCPU and GiB is big enough.
	 * @param vcpu its vCPU
	 * @param gib its memory, in GiB
	 * @return {@code true} when it has at least the size
	 */
	public boolean fitsIn(double vcpu, double gib) {
		return vcpu >= orZero(this.cpu) - TOLERANCE && gib >= orZero(this.memoryGib) - TOLERANCE;
	}

	private static double orZero(@Nullable Double value) {
		return (value != null) ? value : 0.0;
	}

}
