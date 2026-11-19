package io.archsim.engine.pricing;

import org.jspecify.annotations.Nullable;

import io.archsim.model.pricing.Sku;

/**
 * The size an instance type must cover: the vCPU on whole cores, and on the types whose
 * vCPUs the part runs as hyperthread pairs, the vCPU it needs on those.
 *
 * @param cpu the vCPU it needs on whole cores, and on a type of 1 vCPU, which has no
 * sibling; {@code null} for any
 * @param memoryGib the memory it needs, in GiB, or {@code null} for any
 * @param busy the cores one instance keeps busy, for the credit check of a burstable
 * type; {@code null} when unknown (no burstable type then)
 * @param onPairs what it needs of a type it runs as pairs; {@code null}: it runs every
 * type as whole cores
 */
public record MachineSize(@Nullable Double cpu, @Nullable Double memoryGib, @Nullable Busy busy,
		@Nullable OnPairs onPairs) {

	private static final double TOLERANCE = 1e-9;

	/**
	 * A size whose vCPUs are whole cores on every type.
	 * @param cpu the vCPU it needs, or {@code null} for any
	 * @param memoryGib the memory it needs, in GiB, or {@code null} for any
	 * @param busy the cores one instance keeps busy, or {@code null} when unknown
	 */
	public MachineSize(@Nullable Double cpu, @Nullable Double memoryGib, @Nullable Busy busy) {
		this(cpu, memoryGib, busy, null);
	}

	/**
	 * Whether an instance type is big enough: the vCPU of the kind it gives the part, and
	 * the memory.
	 * @param sku the instance type
	 * @return {@code true} when it has at least the size
	 */
	public boolean fitsIn(Sku sku) {
		OnPairs pairs = this.onPairs;
		double vcpu = (pairs != null && pairs.runsAsPairs(sku)) ? pairs.vcpu() : orZero(this.cpu);
		return sku.vcpu() >= vcpu - TOLERANCE && sku.memoryGib() >= orZero(this.memoryGib) - TOLERANCE;
	}

	private static double orZero(@Nullable Double value) {
		return (value != null) ? value : 0.0;
	}

}
