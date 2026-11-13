package io.archsim.benchmarks.machine;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ServiceHttp;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.load.K6Measured;
import io.archsim.benchmarks.load.Traffic;
import io.archsim.benchmarks.machine.ContentionConditions.ServiceCondition;
import io.archsim.benchmarks.machine.Probes.Probe;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonText;

/**
 * pg-mixed's service at a rate under a condition: burners on the condition's CPUs
 * throughout, a warm-up, the statistics reset, then the recorded seconds with k6's own
 * CPU watched; the service's and PostgreSQL's CPU and Windows' view of the machine.
 */
final class ServiceSteps {

	/** Each step's recorded seconds. */
	static final int SECONDS = 20;

	private static final int WARMUP_S = 5;

	/** A burner outlasts the step by this much: the warm-up and the readings. */
	private static final int BURNER_EXTRA_S = 30;

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private static final List<String> CONTAINERS = Stacks.standard().containers("postgres");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MS_PER_S = 1000.0;

	private static final int DECIMALS = 4;

	private final ContentionTools tools;

	private final Instruments running;

	private final K6 k6;

	/**
	 * Steps on a running stack.
	 * @param tools the contention check's tools
	 * @param running what drives and reads the stack
	 */
	ServiceSteps(ContentionTools tools, Instruments running) {
		this.tools = tools;
		this.running = running;
		this.k6 = new K6(running.compose(), tools.tools().summaries());
	}

	/**
	 * Measures a step; its burners are removed either way.
	 * @param rate the rate, in requests per second
	 * @param condition the condition
	 * @return the step
	 */
	ObjectNode measure(int rate, ServiceCondition condition) {
		Probes probes = this.tools.probes();
		probes.clear();
		String tag = ContentionConditions.tag(condition.name());
		condition.burners()
			.forEach((cpu, nice) -> probes
				.launch(new Probe("svc-" + tag + "-" + cpu, cpu, 1.0, SECONDS + BURNER_EXTRA_S, 0, nice)));
		Run run;
		try {
			run = run(rate);
		}
		finally {
			probes.clear();
		}
		return step(rate, condition, run);
	}

	// the warm-up, then the recorded seconds with k6's CPU watched
	private Run run(int rate) {
		Traffic traffic = Traffic.of(PG_MIXED, PG_MIXED.mix(), rate);
		this.k6.run(traffic, new K6.Window(0, WARMUP_S), "contention-warm");
		this.running.services().post(ServiceHttp.API + "/reset");
		Map<String, Long> before = this.running.cgroups().usageUsec(CONTAINERS);
		double fromMs = this.tools.hostMs();
		K6Cpu watch = K6Cpu.watch(this.running.compose(), this.running.ticker(), SECONDS, this.tools.watch());
		JsonNode summary;
		try (watch) {
			summary = this.k6.run(traffic, new K6.Window(0, SECONDS), "contention");
		}
		Optional<Double> k6Cores = watch.cores();
		double toMs = this.tools.hostMs();
		Map<String, Long> after = this.running.cgroups().usageUsec(CONTAINERS);
		return new Run(summary, before, after, fromMs, toMs, k6Cores,
				this.running.services().get(ServiceHttp.API + "/stats"));
	}

	private ObjectNode step(int rate, ServiceCondition condition, Run run) {
		ObjectNode measured = K6Measured.of(run.summary(), SECONDS);
		double seconds = (run.toMs() - run.fromMs()) / MS_PER_S;
		JsonNode postgresMs = run.cpuMs("postgres");
		JsonNode serviceMs = run.cpuMs("api");
		double requests = PythonValues.numberOrZero(run.service(), "requests");
		ObjectNode step = NODES.objectNode().put("condition", condition.name()).put("rateRps", rate);
		ObjectNode burners = step.putObject("burners");
		condition.burners().forEach((cpu, nice) -> burners.put(String.valueOf(cpu), nice ? "nice 19" : "normal"));
		ObjectNode latency = step.putObject("k6");
		List.of("p50", "p95", "p99", "successRate", "throughputRps")
			.forEach((key) -> latency.set(key, PythonValues.get(measured, key)));
		step.set("serviceCpuMsPerRequest", PythonValues.get(run.service(), "cpuMsPerRequest"));
		step.set("serviceCores", cores(serviceMs, seconds));
		step.set("postgresCpuMsPerRequest", (requests != 0) ? per(postgresMs, requests) : PythonValues.none());
		step.set("postgresCores", cores(postgresMs, seconds));
		step.set("k6Cores", run.k6Cores().map(PythonValues::number).orElse(PythonValues.none()));
		JsonNode host = this.tools.counters()
			.window(run.fromMs(), run.toMs())
			.map(JsonNode.class::cast)
			.orElse(PythonValues.none());
		step.set("host", host);
		log(rate, condition, step);
		return step;
	}

	// milliseconds of CPU per request, None when none was used or read
	private static JsonNode per(JsonNode milliseconds, double requests) {
		return PythonText.truthy(milliseconds)
				? PythonValues.number(PythonRound.round(milliseconds.doubleValue() / requests, DECIMALS))
				: PythonValues.none();
	}

	// milliseconds of CPU as busy cores over the run, None when none was used or read
	private static JsonNode cores(JsonNode milliseconds, double seconds) {
		return PythonText.truthy(milliseconds)
				? PythonValues.number(PythonRound.round(milliseconds.doubleValue() / MS_PER_S / seconds, DECIMALS))
				: PythonValues.none();
	}

	private void log(int rate, ServiceCondition condition, JsonNode step) {
		JsonNode host = step.get("host");
		this.tools.tools()
			.log()
			.line(String.format(Locale.ROOT,
					"contention service %5d req/s %-30s service %s ms, postgres %s ms, p95 %s, k6 %s cores, MHz %s, "
							+ "busy LPs %s",
					rate, condition.name(), PythonText.str(step.get("serviceCpuMsPerRequest")),
					PythonText.str(step.get("postgresCpuMsPerRequest")), PythonText.str(step.at("/k6/p95")),
					PythonText.str(step.get("k6Cores")), PythonText.str(PythonValues.get(host, "frequencyMHz")),
					PythonText.str(PythonValues.get(host, "busyLogicalProcessors"))));
	}

	/**
	 * A step's recorded run.
	 *
	 * @param summary k6's summary
	 * @param before each container's CPU counter before it, of those read
	 * @param after each container's CPU counter after it, of those read
	 * @param fromMs its start, on the host's clock in milliseconds
	 * @param toMs its end
	 * @param k6Cores k6's busy cores, when its container was read
	 * @param service the service's statistics after it
	 */
	private record Run(JsonNode summary, Map<String, Long> before, Map<String, Long> after, double fromMs, double toMs,
			Optional<Double> k6Cores, JsonNode service) {

		// a container's CPU over the run in milliseconds, None when not read
		JsonNode cpuMs(String container) {
			Long first = this.before.get(container);
			Long last = this.after.get(container);
			return (first != null && last != null) ? PythonValues.number((last - first) / MS_PER_S)
					: PythonValues.none();
		}

	}

}
