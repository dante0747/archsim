package io.archsim.engine.cost;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.estimate.EstimatedPart;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.PartEstimate;
import io.archsim.engine.load.BaseRate;
import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;

/**
 * What each part handles at the workload's steady base load before a run: the static load
 * estimate at the base rate (a TTL cache missing every read it cannot estimate), with a
 * function's duration from the instant estimate.
 */
final class BaseUsage {

	private BaseUsage() {
	}

	/**
	 * Each part's usage at the base load.
	 * @param document the design as simulated
	 * @param loads the load estimator
	 * @param estimate the design's instant estimate
	 * @return the base load before a run: each part's usage by its id, without the parts
	 * whose load depends on the run
	 */
	static BaseLoad.Estimated of(ArchitectureDocument document, LoadEstimator loads, InstantEstimate estimate) {
		Map<String, Double> stays = stays(estimate);
		Map<String, Usage> usage = new HashMap<>();
		atBase(document, loads).forEach((id, load) -> {
			if (load != null) {
				usage.put(id, usage(load, stays.get(id)));
			}
		});
		return new BaseLoad.Estimated(usage);
	}

	/**
	 * The static load estimate at the base rate, a TTL cache missing every read it cannot
	 * estimate.
	 * @param document the design as simulated
	 * @param loads the load estimator
	 * @return each part's load by its id; {@code null} for a part whose load depends on
	 * the run
	 */
	static Map<String, @Nullable NodeLoad> atBase(ArchitectureDocument document, LoadEstimator loads) {
		Workload workload = document.workload();
		double base = BaseRate.of(workload);
		Map<String, Double> misses = loads.ttlMissShares(document.architecture(), workload, base);
		return loads.estimate(document.architecture(), workload, base, new CacheMisses(misses, 1.0)).nodes();
	}

	private static Usage usage(NodeLoad load, @Nullable Double stay) {
		return new Usage(load.total(), load.read(), load.write(), stay);
	}

	/**
	 * How long a request stays in each part: waiting, working and in its own calls; for
	 * an overloaded part, which has no residence, its time without waiting.
	 * @param estimate the instant estimate
	 * @return the time by part id, for the parts the estimate has one for
	 */
	private static Map<String, Double> stays(InstantEstimate estimate) {
		Map<String, Double> stays = new HashMap<>();
		for (PartEstimate part : estimate.parts()) {
			if (part instanceof EstimatedPart estimated) {
				Double stay = (estimated.meanResidenceMs() != null) ? estimated.meanResidenceMs()
						: estimated.meanHoldMs();
				if (stay != null) {
					stays.put(estimated.id(), stay);
				}
				else {
					// a later part of the same id replaces an earlier one, as in a dict
					stays.remove(estimated.id());
				}
			}
			else {
				stays.remove(part.id());
			}
		}
		return stays;
	}

}
