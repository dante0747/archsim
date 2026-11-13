package io.archsim.benchmarks.calibration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ResourceSampler;
import io.archsim.benchmarks.docker.ServiceHttp;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.load.K6Measured;
import io.archsim.benchmarks.load.Traffic;
import io.archsim.benchmarks.probes.PostgresProbe;
import io.archsim.benchmarks.probes.PostgresSnapshot;
import io.archsim.benchmarks.probes.RedisStats;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;

/**
 * The calibration's passes on a running stack: k6 sends the traffic while the harness
 * reads what the service, PostgreSQL, Redis, Kafka's consumer and each container's cgroup
 * measured.
 */
final class DockerPasses implements CalibrationPasses {

	/** Seconds at rest before the staircase. */
	static final int IDLE_S = 20;

	/** Seconds of warm-up at a pass's rate before its statistics are reset. */
	private static final int WARM_S = 10;

	/** Seconds the consumer gets to finish the last messages after a pass. */
	private static final double LAST_MESSAGES_S = 2;

	private static final double MICROSECONDS_PER_MS = 1000;

	private static final double MIB = 1 << 20;

	private static final int DECIMALS = 4;

	private final Instruments stack;

	private final K6 k6;

	private final PostgresProbe postgres;

	private final Executor sampling;

	private final HarnessLog log;

	/**
	 * The passes on a running stack.
	 * @param stack what drives and reads it
	 * @param k6 k6 on it
	 * @param sampling what reads the containers through a pass:
	 * {@link ResourceSampler#OWN_THREAD}, or a test's own
	 * @param log the log
	 */
	DockerPasses(Instruments stack, K6 k6, Executor sampling, HarnessLog log) {
		this.stack = stack;
		this.k6 = k6;
		this.postgres = new PostgresProbe(stack.compose(), log);
		this.sampling = sampling;
		this.log = log;
	}

	@Override
	public JsonNode settle(Experiment experiment) {
		return Settling.settle(this.stack, this.k6, this.log, experiment);
	}

	@Override
	public JsonNode idle(Experiment experiment) {
		this.log.line("calibrating " + experiment.id() + ": " + IDLE_S + " s idle");
		List<String> services = Stacks.standard().containers(experiment.stack());
		this.stack.services().post(ServiceHttp.API + "/reset");
		Map<String, Long> before = this.stack.cgroups().usageUsec(services);
		this.stack.ticker().sleep(IDLE_S);
		Map<String, Long> after = this.stack.cgroups().usageUsec(services);
		JsonNode service = this.stack.services().get(ServiceHttp.API + "/stats");
		ObjectNode idle = JsonNodeFactory.instance.objectNode().put("seconds", IDLE_S);
		ObjectNode perSecond = idle.putObject("containerCpuMsPerSecond");
		cpuMs(services, before, after).forEach((container, ms) -> perSecond.set(container, PythonValues.isNone(ms) ? ms
				: PythonValues.number(PythonRound.round(ms.doubleValue() / IDLE_S, DECIMALS))));
		idle.put("serviceCpuMsPerSecond",
				PythonRound.round(PythonValues.numberOrZero(service, "cpuMs") / IDLE_S, DECIMALS));
		ObjectNode workingSets = idle.putObject("workingSetMiB");
		for (String container : services) {
			this.stack.compose()
				.container(container)
				.map((id) -> this.stack.cgroups().read(id).workingSet())
				.ifPresent((bytes) -> workingSets.put(container, PythonRound.round(bytes / MIB, 1)));
		}
		return idle;
	}

	@Override
	public JsonNode run(Experiment experiment, String mix, JsonNode rate, String label) {
		this.log.line("calibrating " + experiment.id() + ": " + mix + " at "
				+ PythonFormat.format(rate.doubleValue(), "g") + " req/s (" + label + ")");
		Traffic traffic = Traffic.of(experiment, mix, rate.doubleValue());
		this.k6.run(traffic, new K6.Window(0, WARM_S), "cal-warm");
		reset(experiment);
		Optional<PostgresSnapshot> before = this.postgres.begin();
		List<String> services = Stacks.standard().containers(experiment.stack());
		Map<String, Long> cpuBefore = this.stack.cgroups().usageUsec(services);
		Sampled sampled = sampled(traffic, services, "cal-" + experiment.id() + "-" + label);
		JsonNode summary = sampled.summary();
		Optional<ObjectNode> resources = sampled.resources();
		this.stack.ticker().sleep(LAST_MESSAGES_S);
		Map<String, Long> cpuAfter = this.stack.cgroups().usageUsec(services);
		ObjectNode entry = JsonNodeFactory.instance.objectNode().put("mix", mix);
		entry.set("rateRps", rate);
		entry.set("k6", K6Measured.of(summary, Protocol.CALIBRATION_S));
		entry.set("service", this.stack.services().get(ServiceHttp.API + "/stats"));
		ObjectNode containerCpu = entry.putObject("containerCpuMs");
		cpuMs(services, cpuBefore, cpuAfter).forEach(containerCpu::set);
		resources.ifPresent((read) -> entry.set("resources", read));
		before.flatMap(this.postgres::end).ifPresent((window) -> entry.set("postgres", window));
		return withQueueAndCache(experiment, entry);
	}

	// the statistics a pass is measured from: Redis's, the service's and the consumer's
	private void reset(Experiment experiment) {
		if ("cache".equals(experiment.stack())) {
			this.stack.compose().output("exec", "-T", "redis", "redis-cli", "CONFIG", "RESETSTAT");
		}
		this.stack.services().post(ServiceHttp.API + "/reset");
		if ("kafka".equals(experiment.stack())) {
			this.stack.services().post(ServiceHttp.CONSUMER + "/reset");
		}
	}

	// Kafka's consumer's statistics, the cache stack's Redis's
	private ObjectNode withQueueAndCache(Experiment experiment, ObjectNode entry) {
		if ("kafka".equals(experiment.stack())) {
			entry.set("consumer", this.stack.services().get(ServiceHttp.CONSUMER + "/stats"));
		}
		if ("cache".equals(experiment.stack())) {
			entry.set("redis", RedisStats.read(this.stack.compose()));
		}
		return entry;
	}

	// each container's CPU between two readings in milliseconds, in the stack's order;
	// None where either reading is missing
	private static Map<String, JsonNode> cpuMs(List<String> services, Map<String, Long> before,
			Map<String, Long> after) {
		Map<String, JsonNode> cpu = new LinkedHashMap<>();
		for (String service : services) {
			Long first = before.get(service);
			Long last = after.get(service);
			cpu.put(service, (first != null && last != null) ? PythonValues.number((last - first) / MICROSECONDS_PER_MS)
					: PythonValues.none());
		}
		return cpu;
	}

	// the recorded pass, each container's CPU and memory read through it
	private Sampled sampled(Traffic traffic, List<String> services, String name) {
		ResourceSampler sampler = ResourceSampler.start(this.stack, services, ResourceSampler.Window.CALIBRATION,
				this.sampling);
		JsonNode summary;
		try (sampler) {
			summary = this.k6.run(traffic, new K6.Window(0, Protocol.CALIBRATION_S), name);
		}
		sampler.failure().ifPresent((why) -> this.log.line("  reading the containers failed: " + why));
		return new Sampled(summary, sampler.result());
	}

	/**
	 * A recorded pass and what its sampler read.
	 *
	 * @param summary k6's summary
	 * @param resources each container's CPU and memory through the pass; empty when none
	 * was read
	 */
	private record Sampled(JsonNode summary, Optional<ObjectNode> resources) {

	}

}
