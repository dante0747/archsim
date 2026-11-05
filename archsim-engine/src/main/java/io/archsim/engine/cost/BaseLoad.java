package io.archsim.engine.cost;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.RunReport;
import io.archsim.model.component.ComponentRegistry;

/**
 * What each part handles at the workload's steady base load: estimated before a run
 * ({@link BaseUsage}), or measured in one ({@link MeasuredUsage}).
 */
sealed interface BaseLoad {

	/**
	 * Each part's usage at the base load.
	 * @return the usage by part id; parts without one are left out
	 */
	Map<String, Usage> usage();

	/**
	 * The billable instances of a part.
	 * @param part the part
	 * @param registry the component types, for the part's family
	 * @return its instances and how they were counted
	 */
	InstanceCount instances(CostPart part, ComponentRegistry registry);

	/**
	 * The cost per million successful requests at the base load.
	 * @param monthly the monthly cost
	 * @return the cost; {@code null} before a run, or when no request succeeded
	 */
	@Nullable Double perMillion(double monthly);

	/**
	 * The base load before a run: the static load estimate at the base rate.
	 *
	 * @param usage each part's usage, by its id
	 */
	record Estimated(Map<String, Usage> usage) implements BaseLoad {

		/**
		 * Copies the usage, so the record does not change.
		 * @param usage each part's usage, by its id
		 */
		public Estimated {
			usage = Map.copyOf(usage);
		}

		@Override
		public InstanceCount instances(CostPart part, ComponentRegistry registry) {
			return Instances.of(part, registry);
		}

		@Override
		public @Nullable Double perMillion(double monthly) {
			return null;
		}

	}

	/**
	 * The base load in a run: each part's rate measured while the run's offered load was
	 * at the base, scaled to it.
	 *
	 * @param usage each part's usage, by its id
	 * @param report the run's report
	 * @param scale the base load ÷ the run's offered load, which the run's measured
	 * instances and throughput are scaled by
	 */
	record Measured(Map<String, Usage> usage, RunReport report, double scale) implements BaseLoad {

		/**
		 * Copies the usage, so the record does not change.
		 * @param usage each part's usage, by its id
		 * @param report the run's report
		 * @param scale the base load ÷ the run's offered load
		 */
		public Measured {
			usage = Map.copyOf(usage);
		}

		/**
		 * The part's instances in the run: an autoscaled service's or cluster's average
		 * active instances, scaled to the base load (by one, when the scale is zero).
		 * @param part the part
		 * @param registry the component types, for the part's family
		 * @return its instances and how they were counted
		 */
		@Override
		public InstanceCount instances(CostPart part, ComponentRegistry registry) {
			return Instances.of(part, registry, this.report.node(part.id()).orElse(null),
					(this.scale != 0) ? this.scale : 1.0);
		}

		/**
		 * The cost per million successful requests: the run's throughput scaled to the
		 * base load the month is priced at.
		 * @param monthly the monthly cost
		 * @return the cost; {@code null} when the run measured no successful request
		 */
		@Override
		public @Nullable Double perMillion(double monthly) {
			Double throughput = this.report.summary().throughputRps();
			if (throughput == null) {
				return null;
			}
			double successful = throughput * this.scale;
			return (successful > 0) ? monthly / (successful * CostEstimator.SECONDS_PER_MONTH / CostItem.MILLION)
					: null;
		}

	}

}
