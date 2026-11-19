package io.archsim.benchmarks.campaign;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.comparison.StepAggregation;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ResourceSampler;
import io.archsim.benchmarks.docker.ServiceHttp;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.load.K6Cpu;
import io.archsim.benchmarks.load.K6Measured;
import io.archsim.benchmarks.load.Traffic;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * A step of the load ladder measured on the running stack: three repeats of k6's 5 s
 * warm-up and 20 s window, each container's CPU and memory and k6's own CPU read through
 * the window, the cache's hit ratio or Kafka's queue, and the stack drained before the
 * next repeat. The step keeps how many virtual users k6 held ready, and in how many of
 * its repeats k6 had none free and dropped requests.
 */
public final class Measurement {

	/**
	 * A thread of its own for each watch of a repeat: the containers' sampler and k6's
	 * CPU.
	 */
	static final Executor OWN_THREADS = (task) -> Thread.ofPlatform().daemon().name("repeat-watch").start(task);

	/** Repeats of each step. */
	static final int REPEATS = 3;

	/** Seconds between repeats. */
	private static final double PAUSE_S = 3;

	/** The longest wait for the stack to drain after a repeat. */
	private static final double DRAIN_S = 60;

	/** The longest wait for Kafka's consumers to catch up after a repeat. */
	private static final double BACKLOG_S = 120;

	private static final double POLL_S = 1;

	private static final double BACKLOG_POLL_S = 2;

	/** Kafka's consumer counting the last messages before the queue is read. */
	private static final double LAST_MESSAGES_S = 0.5;

	private static final double MICROSECONDS = 1e6;

	private static final int SHARE_DECIMALS = 3;

	private static final String ACTIVE_QUERIES = "SELECT count(*) FROM pg_stat_activity WHERE datname = 'bench' "
			+ "AND state = 'active' AND pid <> pg_backend_pid()";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final Instruments stack;

	private final K6 k6;

	private final Executor watches;

	private final HarnessLog log;

	/**
	 * Measurements on a running stack.
	 * @param stack what drives and reads it
	 * @param k6 k6 on it
	 * @param watches what runs each watch of a repeat, the containers' sampler and k6's
	 * CPU: {@link #OWN_THREADS}, or a test's own
	 * @param log the log, whose clock dates each repeat
	 */
	public Measurement(Instruments stack, K6 k6, Executor watches, HarnessLog log) {
		this.stack = stack;
		this.k6 = k6;
		this.watches = watches;
		this.log = log;
	}

	/**
	 * A step: three repeats, aggregated. A repeat in which k6 sent nothing (the load
	 * generator failed) is no measurement: it is kept as discarded and run once more. A
	 * repeat in which requests were sent and none succeeded measures a failing system.
	 * @param experiment the experiment
	 * @param rps the step's offered load
	 * @return the step, as {@code measured.json} holds it
	 */
	public ObjectNode step(Experiment experiment, long rps) {
		ArrayNode runs = NODES.arrayNode();
		ArrayNode discarded = NODES.arrayNode();
		for (int run = 1; run <= REPEATS; run++) {
			ObjectNode repeat = once(experiment, rps, run);
			if (!PythonText.truthy(repeat.get("sent"))) {
				this.log.line(
						experiment.id() + ": k6 sent nothing at " + rps + " req/s (run " + run + "); running it again");
				discarded.add(repeat);
				repeat = once(experiment, rps, run);
			}
			runs.add(repeat);
		}
		ObjectNode step = NODES.objectNode().put("offeredRps", rps);
		step.set("runs", runs);
		step.put("at", Timestamps.now(this.log.clock()));
		Traffic traffic = traffic(experiment, rps);
		step.putObject("generator").put("virtualUsers", traffic.virtualUsers()).put("coverS", traffic.coveredSeconds());
		if (!discarded.isEmpty()) {
			step.set("discardedRuns", discarded);
		}
		step.setAll(StepAggregation.aggregate(runs.valueStream().map((run) -> (ObjectNode) run).toList()));
		return step;
	}

	// one repeat of a step
	private ObjectNode once(Experiment experiment, long rps, int run) {
		this.log.line("measuring " + experiment.id() + " at " + rps + " req/s (run " + run + ")");
		JsonNode consumerBefore = prepare(experiment);
		List<String> services = Stacks.standard().containers(experiment.stack());
		Map<String, Long> cpuBefore = this.stack.cgroups().usageUsec(services);
		double start = this.stack.ticker().now();
		ResourceSampler sampler = ResourceSampler.start(this.stack, services, ResourceSampler.Window.MEASURED,
				this.watches);
		K6.Window window = new K6.Window(Protocol.WARMUP_S, Protocol.DURATION_S);
		K6Cpu k6Cpu = K6Cpu.watch(this.stack, window, this.watches);
		JsonNode summary;
		try (sampler; k6Cpu) {
			summary = this.k6.run(traffic(experiment, rps), window, "step-" + experiment.id() + "-" + rps + "-" + run);
		}
		sampler.failure().ifPresent((why) -> this.log.line("  reading the containers failed: " + why));
		double elapsed = this.stack.ticker().now() - start;
		ObjectNode row = NODES.objectNode().put("offeredRps", rps);
		row.setAll(K6Measured.of(summary, Protocol.DURATION_S));
		JsonNode service = this.stack.services().get(ServiceHttp.API + "/stats");
		row.set("serviceCpuMsPerRequest", service.get("cpuMsPerRequest"));
		row.set("containerCpuShare", shares(services, cpuBefore, this.stack.cgroups().usageUsec(services), elapsed));
		k6Cpu.cores().ifPresent((cores) -> row.put("k6Cores", cores));
		sampler.result().ifPresent((resources) -> row.set("resources", resources));
		new StepAfterwards(experiment, service, consumerBefore).record(row);
		return finish(row);
	}

	private static Traffic traffic(Experiment experiment, long rps) {
		return Traffic.of(experiment, experiment.mix(), (double) rps);
	}

	// a cache emptied and the statistics reset before a repeat; Kafka's consumer's
	// count read
	private JsonNode prepare(Experiment experiment) {
		if (experiment.hasCache()) {
			this.stack.compose()
				.output("exec", "-T", "cache-ttl".equals(experiment.stack()) ? "redis-ttl" : "redis", "redis-cli",
						"FLUSHALL");
		}
		this.stack.services().post(ServiceHttp.API + "/reset");
		return "kafka".equals(experiment.stack()) ? this.stack.services().get(ServiceHttp.CONSUMER + "/stats")
				: PythonValues.none();
	}

	// the repeat dated and logged, and a pause before the next
	private ObjectNode finish(ObjectNode row) {
		row.put("at", Timestamps.now(this.log.clock()));
		this.log.line("  p50 " + format(row.get("p50"), ".2f") + " ms, p95 " + format(row.get("p95"), ".2f")
				+ " ms, success " + format(row.get("successRate"), ".3f") + ", "
				+ PythonFormat.format(row.get("throughputRps").doubleValue(), ".0f") + " req/s");
		this.stack.ticker().sleep(PAUSE_S);
		return row;
	}

	/**
	 * Waits, a minute at most, until the service answers its health check and PostgreSQL
	 * runs no query: a step past the knee can leave queued requests or a restarting
	 * service behind (run 4's hot-row at 1,126 req/s: after the first repeat no request
	 * succeeded), and the next repeat must not start on top of them.
	 * @return how long it waited, and whether the service was healthy and the database
	 * idle
	 */
	ObjectNode drain() {
		double start = this.stack.ticker().now();
		boolean healthy = false;
		boolean idle = false;
		while (this.stack.ticker().now() - start < DRAIN_S && !idle) {
			if (!healthy) {
				healthy = answers();
				if (!healthy) {
					this.stack.ticker().sleep(POLL_S);
					continue;
				}
			}
			idle = "0".equals(this.stack.compose()
				.result("exec", "-T", "postgres", "psql", "-U", "bench", "-d", "bench", "-tAq", "-c", ACTIVE_QUERIES)
				.output()
				.strip());
			if (!idle) {
				this.stack.ticker().sleep(POLL_S);
			}
		}
		if (!(healthy && idle)) {
			this.log.line("  not drained after " + PythonFormat.format(DRAIN_S, "g") + " s (service healthy: "
					+ (healthy ? "True" : "False") + ", database idle: " + (idle ? "True" : "False") + ")");
		}
		return NODES.objectNode()
			.put("seconds", PythonRound.round(this.stack.ticker().now() - start, 1))
			.put("healthy", healthy)
			.put("databaseIdle", idle);
	}

	private boolean answers() {
		try {
			this.stack.services().get(ServiceHttp.API + "/health");
			return true;
		}
		catch (UncheckedIOException ex) {
			return false;
		}
	}

	// each container's busy cores over the whole k6 command, its start and stop included
	private static ObjectNode shares(List<String> services, Map<String, Long> before, Map<String, Long> after,
			double elapsed) {
		ObjectNode shares = NODES.objectNode();
		for (String service : services) {
			Long first = before.get(service);
			Long last = after.get(service);
			if (first != null && last != null) {
				shares.put(service, PythonRound.round((last - first) / MICROSECONDS / elapsed, SHARE_DECIMALS));
			}
		}
		return shares;
	}

	// a figure as the log shows it: 0 for none
	private static String format(JsonNode value, String spec) {
		return PythonFormat.format(PythonValues.isNone(value) ? 0.0 : value.doubleValue(), spec);
	}

	/**
	 * What a repeat leaves to read after k6: the cache's hit ratio, Kafka's queue (and
	 * its backlog drained), or the stack drained.
	 */
	private final class StepAfterwards {

		private final Experiment experiment;

		private final JsonNode service;

		private final JsonNode consumerBefore;

		StepAfterwards(Experiment experiment, JsonNode service, JsonNode consumerBefore) {
			this.experiment = experiment;
			this.service = service;
			this.consumerBefore = consumerBefore;
		}

		void record(ObjectNode row) {
			JsonNode counters = this.service.path("counters");
			if (this.experiment.hasCache()) {
				double hits = PythonValues.numberOrZero(counters, "cache_hit:measure");
				double misses = PythonValues.numberOrZero(counters, "cache_miss:measure");
				row.set("cacheHitRatio",
						(hits + misses > 0) ? PythonValues.number(hits / (hits + misses)) : PythonValues.none());
			}
			if ("kafka".equals(this.experiment.stack())) {
				row.set("queue", queue(counters));
				return;
			}
			row.set("drain", drain());
		}

		// what Kafka's consumers did with the step's messages, the backlog let drain
		private ObjectNode queue(JsonNode counters) {
			Measurement.this.stack.ticker().sleep(LAST_MESSAGES_S);
			ServiceHttp services = Measurement.this.stack.services();
			JsonNode consumer = services.get(ServiceHttp.CONSUMER + "/stats");
			long produced = (long) (PythonValues.numberOrZero(counters, "produced:measure")
					+ PythonValues.numberOrZero(counters, "produced:warm"));
			long consumedNow = (long) (consumer.get("consumed").doubleValue() - consumed(this.consumerBefore));
			JsonNode endToEnd = consumer.path("timings").path("end_to_end:measure");
			ObjectNode queue = NODES.objectNode().put("produced", produced).put("consumedDuringStep", consumedNow);
			queue.put("consumedRps",
					PythonRound.round((double) consumedNow / (Protocol.WARMUP_S + Protocol.DURATION_S), 2));
			queue.put("backlogAtEnd", Math.max(0, produced - consumedNow));
			queue.set("p95MessageAgeMs", PythonValues.get(endToEnd, "p95"));
			queue.set("meanMessageAgeMs", PythonValues.get(endToEnd, "mean"));
			double deadline = Measurement.this.stack.ticker().now() + BACKLOG_S;
			while (Measurement.this.stack.ticker().now() < deadline
					&& services.get(ServiceHttp.CONSUMER + "/stats").get("consumed").doubleValue()
							- consumed(this.consumerBefore) < produced) {
				Measurement.this.stack.ticker().sleep(BACKLOG_POLL_S);
			}
			services.post(ServiceHttp.CONSUMER + "/reset");
			return queue;
		}

		private static double consumed(JsonNode consumer) {
			return PythonValues.numberOrZero(consumer, "consumed");
		}

	}

}
