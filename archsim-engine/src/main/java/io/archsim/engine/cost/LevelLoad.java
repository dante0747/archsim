package io.archsim.engine.cost;

import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.RunReport;
import io.archsim.model.component.ComponentRegistry;

/**
 * What each part handles at one load of the workload, its steady base or its peak:
 * estimated before a run ({@link EstimatedUsage}), or measured in one
 * ({@link MeasuredUsage}).
 */
sealed interface LevelLoad {

	/**
	 * The load this is.
	 * @return the level
	 */
	LoadLevel level();

	/**
	 * Each part's usage at the load.
	 * @return the usage by part id; parts without one are left out
	 */
	Map<String, Usage> usage();

	/**
	 * The replicas each part keeps busy at the load (utilization × replicas), for an
	 * autoscaled service's count.
	 * @return the busy replicas by part id; parts without them are left out
	 */
	Map<String, Double> busyReplicas();

	/**
	 * The billable instances of a part that does not autoscale.
	 * @param part the part
	 * @param registry the component types, for the part's family
	 * @return its instances and how they were counted
	 */
	InstanceCount instances(CostPart part, ComponentRegistry registry);

	/**
	 * The successful requests a second at the load.
	 * @return the rate; {@code null} before a run, or when the run measured none
	 */
	@Nullable Double successfulRps();

	/**
	 * The cost per million successful requests at the load.
	 * @param monthly the monthly cost
	 * @return the cost; {@code null} before a run, or when no request succeeded
	 */
	default @Nullable Double perMillion(double monthly) {
		return perMillion(monthly, successfulRps());
	}

	/**
	 * The cost per million successful requests of a month at one load.
	 * @param monthly the monthly cost
	 * @param successfulRps the successful requests a second; {@code null} when unknown
	 * @return the cost; {@code null} when no request succeeded or it is unknown
	 */
	static @Nullable Double perMillion(double monthly, @Nullable Double successfulRps) {
		return (successfulRps != null && successfulRps > 0)
				? monthly / (successfulRps * CostEstimator.SECONDS_PER_MONTH / CostItem.MILLION) : null;
	}

	/**
	 * A load before a run: the static load estimate at its rate.
	 *
	 * @param level the load
	 * @param usage each part's usage, by its id
	 * @param busyReplicas the replicas each part keeps busy, by its id
	 */
	record Estimated(LoadLevel level, Map<String, Usage> usage, Map<String, Double> busyReplicas) implements LevelLoad {

		/**
		 * Copies the maps, so the record does not change.
		 * @param level the load
		 * @param usage each part's usage, by its id
		 * @param busyReplicas the replicas each part keeps busy, by its id
		 */
		public Estimated {
			usage = Map.copyOf(usage);
			busyReplicas = Map.copyOf(busyReplicas);
		}

		@Override
		public InstanceCount instances(CostPart part, ComponentRegistry registry) {
			return Instances.of(part, registry);
		}

		@Override
		public @Nullable Double successfulRps() {
			return null;
		}

	}

	/**
	 * A load in a run: each part's rate measured while the run's offered load was at it,
	 * scaled to it.
	 *
	 * @param level the load
	 * @param usage each part's usage, by its id
	 * @param busyReplicas the replicas each part keeps busy, by its id
	 * @param run the run's report and the load's rate ÷ the run's offered load, which the
	 * run's measured instances and throughput are scaled by
	 */
	record Measured(LoadLevel level, Map<String, Usage> usage, Map<String, Double> busyReplicas,
			ScaledRun run) implements LevelLoad {

		/**
		 * Copies the maps, so the record does not change.
		 * @param level the load
		 * @param usage each part's usage, by its id
		 * @param busyReplicas the replicas each part keeps busy, by its id
		 * @param run the run's report and its scale
		 */
		public Measured {
			usage = Map.copyOf(usage);
			busyReplicas = Map.copyOf(busyReplicas);
		}

		/**
		 * The part's instances in the run: a serverless function's average active
		 * instances, scaled to the load (by one, when the scale is zero); any other
		 * part's as before a run.
		 * @param part the part
		 * @param registry the component types, for the part's family
		 * @return its instances and how they were counted
		 */
		@Override
		public InstanceCount instances(CostPart part, ComponentRegistry registry) {
			return Instances.of(part, registry, this);
		}

		/**
		 * The successful requests a second: the run's throughput scaled to the load.
		 * @return the rate; {@code null} when the run measured no throughput
		 */
		@Override
		public @Nullable Double successfulRps() {
			Double throughput = this.run.report().summary().throughputRps();
			return (throughput != null) ? throughput * this.run.scale() : null;
		}

	}

	/**
	 * A run's report, and the load's rate ÷ the run's offered load.
	 *
	 * @param report the run's report
	 * @param scale the load's rate ÷ the run's offered load
	 */
	record ScaledRun(RunReport report, double scale) {
	}

}
