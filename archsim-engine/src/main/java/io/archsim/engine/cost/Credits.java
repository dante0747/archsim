package io.archsim.engine.cost;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.text.HalfUp;
import io.archsim.engine.text.NumberText;
import io.archsim.model.pricing.Sku;

/**
 * The surplus CPU credits of a burstable instance type whose CPU at the base load is
 * above its baseline: their monthly cost in unlimited mode, or a note that the type is
 * throttled to its baseline when its credits run out.
 *
 * @param monthly the credits' monthly cost; zero when the type has no credit price
 * @param note what the credits are, in words
 */
record Credits(double monthly, String note) {

	/** CPU at the base load this close to the baseline counts as under it. */
	private static final double TOLERANCE = 1e-9;

	private static final double PERCENT = 100;

	/**
	 * The surplus credits of an instance type at the base load.
	 * @param sku the instance type; {@code null} for none
	 * @param baseBusy the CPU cores one instance keeps busy at the base load
	 * @param instances the part's instances
	 * @return the credits; {@code null} when the type is not burstable or stays under its
	 * baseline
	 */
	static @Nullable Credits of(@Nullable Sku sku, @Nullable Double baseBusy, double instances) {
		if (sku == null || !sku.burstable() || sku.baselineCpu() == null || baseBusy == null) {
			return null;
		}
		double baseline = sku.baselineCpu() * sku.vcpu();
		double over = baseBusy - baseline;
		if (over <= TOLERANCE) {
			return null;
		}
		String what = HalfUp.fixed(baseBusy, 2) + " cores busy at the base load, over its baseline of "
				+ HalfUp.fixed(baseline, 2) + " (" + NumberText.plain(HalfUp.round(sku.baselineCpu() * PERCENT, 0))
				+ "% of each vCPU)";
		Double credit = sku.creditVcpuHour();
		if (credit == null) {
			return new Credits(0.0, what + ": it is throttled to its baseline when its CPU credits run out");
		}
		double monthly = over * credit * CostEstimator.HOURS_PER_MONTH * instances;
		return new Credits(monthly, "+ $" + HalfUp.fixed(monthly, 2) + " a month of surplus CPU credits: " + what + ", "
				+ HalfUp.fixed(over, 2) + " vCPU × $" + NumberText.plain(credit) + " per vCPU-hour");
	}

}
