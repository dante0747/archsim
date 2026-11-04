package io.archsim.engine.pricing;

import io.archsim.model.pricing.Sku;

/**
 * The CPU cores one instance of a part keeps busy.
 *
 * @param peak at the workload's peak
 * @param base at its steady base load
 */
public record Busy(double peak, double base) {

	/**
	 * A burstable type is picked only when the part's CPU at the base load stays under
	 * this share of the type's baseline: it then earns credits all the time and spends
	 * them only in peaks.
	 */
	public static final double BURST_BASE_SHARE = 0.8;

	/**
	 * A burstable type is picked only when the part's CPU at the peak also stays under
	 * this share of the type's vCPUs, the utilization auto-sizing aims at.
	 */
	public static final double BURST_PEAK_SHARE = 0.6;

	private static final double TOLERANCE = 1e-12;

	/**
	 * Whether a burstable type can run the part on its credits: its CPU at the base load
	 * stays under 80% of the type's baseline, and at the peak under 60% of its vCPUs.
	 * @param sku the instance type
	 * @return {@code false} for a type that is not burstable
	 */
	public boolean runsOnCredits(Sku sku) {
		Double baseline = sku.baselineCpu();
		if (!sku.burstable() || baseline == null) {
			return false;
		}
		return this.base <= BURST_BASE_SHARE * baseline * sku.vcpu() + TOLERANCE
				&& this.peak <= BURST_PEAK_SHARE * sku.vcpu() + TOLERANCE;
	}

}
