package io.archsim.benchmarks.baselines;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * The share of a cache's lookups that miss, as the bottleneck arithmetic takes it: what
 * the service counted in the top calibration pass, the rate the other inputs are
 * calibrated at.
 *
 * @param share the share of lookups that missed
 * @param how where it comes from, in the file's words
 */
record CacheMisses(double share, String how) {

	/**
	 * The misses of a calibration's top pass.
	 * @param calibration the run's {@code calibration.json}
	 * @return its misses over its lookups; every lookup a miss when it counted none, the
	 * most a database behind the cache can be sent
	 */
	static CacheMisses of(JsonNode calibration) {
		JsonNode counters = calibration.at("/passes/high/service/counters");
		long misses = (long) PythonValues.numberOrZero(counters, "cache_miss:measure");
		long lookups = misses + (long) PythonValues.numberOrZero(counters, "cache_hit:measure");
		if (lookups == 0) {
			return new CacheMisses(1.0, "the top calibration pass counted no lookups: every lookup taken as a miss");
		}
		return new CacheMisses((double) misses / lookups,
				"the top calibration pass's misses over its lookups: " + misses + " of " + lookups);
	}

	/**
	 * The misses as {@code baselines.json} records them.
	 * @return {@code share} and {@code how}
	 */
	ObjectNode json() {
		return JsonNodeFactory.instance.objectNode().put("share", Units.precise(this.share)).put("how", this.how);
	}

}
