package io.archsim.benchmarks.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;

/**
 * The calibration staircase's rule (protocol v6): each rising pass at most 65% of the
 * service's CPUs at the pass before's CPU per request (the whole process's, idle
 * included, so the climb undershoots while the cost still falls), and at most half of any
 * other capacity that pass implies (the experiment's connection pool, the lock of a row
 * every request updates, the Kafka consumers), whose times include the network and the
 * driver and past which a backlog would carry into the next pass. The staircase stops
 * once a pass's p95 passes 10 ms, after five rises, or when the next rate would not be a
 * tenth above the last.
 */
final class Staircase {

	/** A rising pass's most of the service's CPUs (v5: 0.35). */
	static final double CPU_CAP = 0.65;

	/** A rising pass's most of every other capacity the pass before implies (v5 too). */
	static final double BOUND_SHARE = 0.5;

	/** The p95 above which the staircase stops rising (v5 and v6). */
	static final double P95_CAP_MS = 10.0;

	/** The most rises above 50 req/s (v5: 3). */
	static final int MAX_RISES = 5;

	/** How much above the last a next step must be, or the staircase stops (v5: 1.25). */
	static final double MIN_RISE = 1.1;

	/** Kafka's consumers. */
	private static final int CONSUMERS = 3;

	private static final double MILLISECONDS = 1000;

	/** The least CPU per request a bound divides by. */
	private static final double LEAST_CPU_MS = 1e-6;

	private final int serviceCpus;

	/**
	 * The staircase of a service with so many CPUs.
	 * @param serviceCpus the CPUs of the service under test, its cpuset's
	 */
	Staircase(int serviceCpus) {
		this.serviceCpus = serviceCpus;
	}

	/**
	 * The rule as {@code calibration.json} records it.
	 * @return {@code cpuCap}, {@code boundShare}, {@code p95CapMs}, {@code maxRises},
	 * {@code minRise}
	 */
	static ObjectNode rule() {
		return JsonNodeFactory.instance.objectNode()
			.put("cpuCap", CPU_CAP)
			.put("boundShare", BOUND_SHARE)
			.put("p95CapMs", P95_CAP_MS)
			.put("maxRises", MAX_RISES)
			.put("minRise", MIN_RISE);
	}

	/**
	 * The service's own {@code /stats} and, behind chain's gateway, the API's.
	 * @param service the {@code /stats} of the service k6 calls
	 * @return each hop's
	 */
	static List<JsonNode> hops(JsonNode service) {
		List<JsonNode> hops = new ArrayList<>(List.of(service));
		JsonNode upstream = PythonValues.get(service, "upstream");
		if (upstream.isObject() && !upstream.isEmpty()) {
			hops.add(upstream);
		}
		return hops;
	}

	/**
	 * The staircase's next rate after a pass.
	 * @param experiment the experiment
	 * @param entry the pass
	 * @return the rate, rounded to a whole req/s; empty to stop
	 */
	OptionalDouble nextRise(Experiment experiment, JsonNode entry) {
		JsonNode p95 = PythonValues.get(entry.get("k6"), "p95");
		if (!PythonValues.isNone(p95) && p95.doubleValue() > P95_CAP_MS) {
			return OptionalDouble.empty();
		}
		double next = Math.min(CPU_CAP * cpuBound(entry), BOUND_SHARE * otherBound(experiment, entry));
		double rate = entry.get("rateRps").doubleValue();
		return (next >= rate * MIN_RISE) ? OptionalDouble.of(Math.rint(next)) : OptionalDouble.empty();
	}

	/**
	 * The most pessimistic capacity a low pass implies: each hop's CPU at its cost per
	 * request, the connection pool, the hot row's lock, the consumers.
	 * @param experiment the experiment
	 * @param low the pass
	 * @return req/s
	 */
	double capacityBound(Experiment experiment, JsonNode low) {
		return Math.min(cpuBound(low), otherBound(experiment, low));
	}

	// the rate at which the service's CPUs would fill at a pass's CPU per request: the
	// whole process's, idle included; chain's busier hop
	private double cpuBound(JsonNode entry) {
		double cpu = hops(entry.get("service")).stream()
			.mapToDouble((hop) -> hop.get("cpuMsPerRequest").doubleValue())
			.max()
			.orElseThrow();
		return MILLISECONDS * this.serviceCpus / Math.max(cpu, LEAST_CPU_MS);
	}

	// the capacity of the experiment's pool, of a hot row's lock and of the consumers;
	// infinite when none applies
	private static double otherBound(Experiment experiment, JsonNode entry) {
		JsonNode service = entry.get("service");
		double bound = Double.POSITIVE_INFINITY;
		for (JsonNode hop : hops(service)) {
			for (String query : List.of("db_read", "db_write")) {
				JsonNode timing = hop.get("timings").get(query);
				if (timing != null) {
					bound = Math.min(bound, experiment.connections() * MILLISECONDS / timing.get("mean").doubleValue());
				}
			}
		}
		if (experiment.updatesOneRow()) {
			bound = Math.min(bound, MILLISECONDS / service.at("/timings/db_write/mean").doubleValue());
		}
		if ("kafka".equals(experiment.stack())) {
			bound = Math.min(bound,
					CONSUMERS * MILLISECONDS / entry.at("/consumer/timings/consume/mean").doubleValue());
		}
		return bound;
	}

}
