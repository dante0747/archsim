package io.archsim.engine.cost;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.estimate.EstimatedPart;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.PartEstimate;
import io.archsim.engine.load.CacheMisses;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.load.NodeLoad;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;

/**
 * What each part handles at a load of the workload before a run: the static load estimate
 * at its rate (a TTL cache missing every read it cannot estimate), with a function's
 * duration and the replicas a part keeps busy from the instant estimate.
 */
final class EstimatedUsage {

	private EstimatedUsage() {
	}

	/**
	 * Each part's usage at a load.
	 * @param document the design as simulated
	 * @param loads the load estimator
	 * @param estimate the design's instant estimate
	 * @param level the load
	 * @return the load before a run: each part's usage by its id, without the parts whose
	 * load depends on the run
	 */
	static LevelLoad.Estimated of(ArchitectureDocument document, LoadEstimator loads, InstantEstimate estimate,
			LoadLevel level) {
		Map<String, Double> stays = stays(estimate);
		Map<String, Usage> usage = new HashMap<>();
		at(document, loads, level).forEach((id, load) -> {
			if (load != null) {
				usage.put(id, new Usage(load.total(), load.read(), load.write(), stays.get(id)));
			}
		});
		return new LevelLoad.Estimated(level, usage, busyReplicas(estimate, usage));
	}

	/**
	 * The static load estimate at a load's rate, a TTL cache missing every read it cannot
	 * estimate.
	 * @param document the design as simulated
	 * @param loads the load estimator
	 * @param level the load
	 * @return each part's load by its id; {@code null} for a part whose load depends on
	 * the run
	 */
	static Map<String, @Nullable NodeLoad> at(ArchitectureDocument document, LoadEstimator loads, LoadLevel level) {
		Workload workload = document.workload();
		double rate = level.rate(workload);
		Map<String, Double> misses = loads.ttlMissShares(document.architecture(), workload, rate);
		return loads.estimate(document.architecture(), workload, rate, new CacheMisses(misses, 1.0)).nodes();
	}

	/**
	 * The replicas each part keeps busy at a load: the instant estimate's busy replicas
	 * (the larger of its slot and CPU utilization × its pools) per request it received,
	 * times its requests at the load.
	 * @param estimate the instant estimate
	 * @param usage each part's usage at the load
	 * @return the busy replicas by part id, for the parts the estimate has them for
	 */
	private static Map<String, Double> busyReplicas(InstantEstimate estimate, Map<String, Usage> usage) {
		Map<String, Double> busy = new HashMap<>();
		for (PartEstimate part : estimate.parts()) {
			Usage at = usage.get(part.id());
			Double perRequest = (part instanceof EstimatedPart estimated) ? busyPerRequest(estimated) : null;
			if (at != null && perRequest != null) {
				busy.put(part.id(), perRequest * at.rps());
			}
		}
		return busy;
	}

	private static @Nullable Double busyPerRequest(EstimatedPart part) {
		Double utilization = part.utilization();
		Double arrivals = part.arrivalRps();
		return (utilization != null && arrivals != null && arrivals > 0) ? utilization * part.pools() / arrivals : null;
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
