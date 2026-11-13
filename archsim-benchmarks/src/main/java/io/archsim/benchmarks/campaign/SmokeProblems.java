package io.archsim.benchmarks.campaign;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * What a smoke test found wrong, in the order {@code run.py} checked it: every request k6
 * meant to send was sent and succeeded, and was counted by the service (on chain, at the
 * gateway and at the API behind it) as a request and in its handler's timings, by
 * PostgreSQL's timings, as a cache hit or miss, by Redis, by the garbage collector's
 * counts, by gunicorn's workers, and as a produced message.
 */
final class SmokeProblems {

	/** k6 may send this share of the requests it should have, its first second lost. */
	private static final double SENT_SHARE = 0.95;

	private SmokeProblems() {
	}

	/**
	 * The problems a smoke test found.
	 * @param experiment the experiment
	 * @param sent k6's measured window ({@code K6Measured})
	 * @param stats the service's {@code /stats} after it
	 * @param wanted the requests k6 should have sent
	 * @return the problems, each a sentence; none when it passed
	 */
	static List<String> of(Experiment experiment, JsonNode sent, JsonNode stats, int wanted) {
		long count = (long) PythonValues.numberOrZero(sent, "sent");
		List<String> problems = new ArrayList<>(k6(sent, count, wanted));
		services(experiment, stats).forEach((service) -> problems.addAll(service.problems(count)));
		problems.addAll(stores(experiment, stats, count));
		if (!PythonValues.get(stats, "gc").has("collections")) {
			problems.add("the service reported no garbage collection counts");
		}
		problems.addAll(workers(experiment, stats));
		problems.addAll(producer(experiment, stats, count));
		return problems;
	}

	// k6's own account: every request sent, none dropped or failed
	private static List<String> k6(JsonNode sent, long count, int wanted) {
		List<String> problems = new ArrayList<>();
		if (count < SENT_SHARE * wanted) {
			problems.add("k6 sent " + count + " requests of the " + wanted + " it should have");
		}
		if (PythonText.truthy(PythonValues.get(sent, "dropped"))) {
			problems.add("k6 dropped " + PythonText.str(sent.get("dropped")) + " requests (no VU free to send them)");
		}
		JsonNode succeeded = PythonValues.get(sent, "succeeded");
		if (PythonText.truthy(PythonValues.get(sent, "failed")) || !equalTo(succeeded, count)) {
			long failed = count - (long) (PythonText.truthy(succeeded) ? succeeded.doubleValue() : 0);
			problems.add(failed + " of " + count + " requests failed");
		}
		return problems;
	}

	// the service k6 calls and, on chain, the API behind it
	private static List<Counted> services(Experiment experiment, JsonNode stats) {
		if (!"chain".equals(experiment.stack())) {
			return List.of(new Counted("the service", stats));
		}
		JsonNode upstream = PythonValues.get(stats, "upstream");
		return List.of(new Counted("the service", stats), new Counted("the API behind the gateway",
				PythonText.truthy(upstream) ? upstream : JsonNodeFactory.instance.objectNode()));
	}

	// PostgreSQL's calls, the cache's lookups, Redis's GETs
	private static List<String> stores(Experiment experiment, JsonNode stats, long count) {
		List<String> problems = new ArrayList<>();
		String stack = experiment.stack();
		if (Stacks.standard().callsPostgresEveryRequest(stack)) {
			JsonNode timings = experiment.postgresClient(stats).path("timings");
			long queries = (long) (timed(timings, "db_read") + timed(timings, "db_write"));
			if (queries != count) {
				problems.add("PostgreSQL calls timed: " + queries + ", k6 sent " + count);
			}
		}
		JsonNode counters = stats.path("counters");
		if (Stacks.standard().isCache(stack)) {
			long looked = (long) (PythonValues.numberOrZero(counters, "cache_hit:measure")
					+ PythonValues.numberOrZero(counters, "cache_miss:measure"));
			if (looked != count) {
				problems.add("the cache counted " + looked + " hits and misses, k6 sent " + count);
			}
		}
		if ("cache-ttl".equals(stack)) {
			JsonNode gets = PythonValues.get(stats.path("redis").path("get"), "calls");
			if (!equalTo(gets, count)) {
				problems.add("Redis counted " + PythonText.str(gets) + " GET commands, k6 sent " + count);
			}
		}
		return problems;
	}

	// gunicorn's workers, each holding a slot of the shared statistics
	private static List<String> workers(Experiment experiment, JsonNode stats) {
		Integer workers = Experiments.standard().runtime(experiment).workers();
		JsonNode reported = PythonValues.get(stats, "workers");
		if ("python".equals(experiment.runtime()) && workers != null && !equalTo(reported, workers)) {
			return List.of(PythonText.str(reported) + " gunicorn workers hold a statistics slot, not " + workers);
		}
		return List.of();
	}

	// the messages Kafka's producer counted: 0 when it counted none at all
	private static List<String> producer(Experiment experiment, JsonNode stats, long count) {
		JsonNode counters = stats.path("counters");
		JsonNode produced = counters.has("produced:measure") ? counters.get("produced:measure")
				: PythonValues.integer(0);
		if ("kafka".equals(experiment.stack()) && !equalTo(produced, count)) {
			return List.of("the producer counted " + PythonText.str(produced) + " messages, k6 sent " + count);
		}
		return List.of();
	}

	// the calls a service timed, 0 when it timed none
	private static double timed(JsonNode timings, String key) {
		return PythonValues.numberOrZero(timings.path(key), "count");
	}

	private static boolean equalTo(JsonNode value, long count) {
		return PythonValues.equal(value, PythonValues.integer(count));
	}

	/**
	 * A service's {@code /stats}, and what to call it in a problem.
	 *
	 * @param who the service, as a sentence's subject
	 * @param stats its {@code /stats}
	 */
	private record Counted(String who, JsonNode stats) {

		// it counted each request, timed each handler and reported its CPU
		List<String> problems(long count) {
			List<String> problems = new ArrayList<>();
			JsonNode requests = PythonValues.get(this.stats, "requests");
			if (!equalTo(requests, count)) {
				problems.add(this.who + " counted " + PythonText.str(requests) + " requests, k6 sent " + count);
			}
			JsonNode handled = PythonValues.get(this.stats.path("timings").path("handler"), "count");
			if (!equalTo(handled, count)) {
				problems.add(this.who + " timed " + PythonText.str(handled) + " handlers, k6 sent " + count);
			}
			if (!PythonText.truthy(PythonValues.get(this.stats, "cpuMs"))) {
				problems.add(this.who + " reported no CPU time");
			}
			return problems;
		}

	}

}
