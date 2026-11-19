package io.archsim.engine.sizing;

import io.archsim.engine.pricing.OnPairs;
import io.archsim.model.component.Resources;

/**
 * A part's CPU size on each kind of vCPU, where the machine it lands on decides which its
 * vCPUs are (ADR-0091): whole cores, or hyperthread pairs, which need more vCPU for the
 * same work.
 *
 * @param onWholeCores its size on whole cores, and on a machine of 1 vCPU, which has no
 * sibling
 * @param onPairs its size on a machine of 2 or more vCPU that it runs as hyperthread
 * pairs
 * @param byFamily whether a whole-core family's machines give it whole cores, as the
 * default counts them; {@code false}: every machine of 2 or more vCPU gives it pairs, as
 * a sibling speed set on the part or the design says
 */
public record CpuKinds(CpuSize onWholeCores, CpuSize onPairs, boolean byFamily) {

	/**
	 * What the part needs of an instance type it runs as pairs.
	 * @param provider the price snapshot's provider, whose whole-core families give whole
	 * cores where the part goes by the default
	 * @return the need on pairs
	 */
	public OnPairs need(String provider) {
		return new OnPairs(this.onPairs.vcpu(), this.byFamily ? provider : null);
	}

	/**
	 * The size on a machine of a standard shape, which has no family: pairs from 2 vCPU
	 * on.
	 * @param vcpu the shape's vCPU
	 * @return the size of the kind its vCPUs are
	 */
	public CpuSize onShapeOf(double vcpu) {
		return (vcpu >= Resources.FEWEST_PAIRED_VCPU) ? this.onPairs : this.onWholeCores;
	}

}
