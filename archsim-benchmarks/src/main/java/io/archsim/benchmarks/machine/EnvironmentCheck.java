package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.calibration.Settling;
import io.archsim.benchmarks.docker.CpuTopology;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ServiceHttp;
import io.archsim.benchmarks.docker.StackLifecycle;
import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.load.K6Measured;
import io.archsim.benchmarks.load.Traffic;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The environment check ({@code envcheck}): the machine on pg-mixed's workload. Which
 * CPUs share a physical core, how much CPU Docker's VM loses to steal, and the service's
 * and PostgreSQL's CPU per request and the latency across rates, with k6 on the CPUs the
 * runner gives it and again with the service's physical core left to the service alone.
 * Writes {@code results/envcheck/<at>.json}.
 */
public final class EnvironmentCheck {

	/** The rates each layout steps through. */
	static final List<Integer> RATES = List.of(50, 100, 200, 400, 800, 1600);

	/** Each rate's recorded seconds. */
	static final int SECONDS = 15;

	/**
	 * Windows' default timer interval: latencies that pile up near it come from the host.
	 */
	static final double TIMER_TICK_MS = 15.625;

	private static final double NEAR_TICK_MS = 1.5;

	private static final int WARMUP_S = 5;

	private static final String STACK = "postgres";

	private static final Experiment PG_MIXED = Experiments.standard().get("pg-mixed");

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MS_PER_S = 1000.0;

	private static final int DECIMALS = 4;

	private final CheckTools tools;

	/**
	 * The check on this machine.
	 * @param tools what it runs with
	 */
	public EnvironmentCheck(CheckTools tools) {
		this.tools = tools;
	}

	/**
	 * Runs the check, on a stack started afresh and stopped afterwards, and writes it.
	 * @param results the results folder
	 * @return the file written
	 */
	public Path run(Path results) {
		Map<Integer, List<Integer>> siblings = CpuTopology.siblings(this.tools.instruments().compose())
			.orElse(Map.of());
		Map<String, String> sets = this.tools.cpusets().of(STACK, this.tools.environment());
		String started = Timestamps.now(this.tools.log().clock());
		StackLifecycle stacks = this.tools.stacks();
		Instruments running = stacks.up(STACK);
		ObjectNode layouts = NODES.objectNode();
		try {
			Settling.settle(running, new K6(running.compose(), this.tools.summaries()), this.tools.log(), PG_MIXED);
			layouts(sets, siblings)
				.forEach((name, k6Cpus) -> layouts.set(name, sweep(running, sets, siblings, k6Cpus)));
		}
		finally {
			stacks.down(STACK);
		}
		ObjectNode check = NODES.objectNode().put("at", started);
		check.set("environment", this.tools.machine().get());
		check.put("workload", PG_MIXED.id());
		check.put("how", "each rate: " + WARMUP_S + " s warm-up, then " + SECONDS + " s recorded; CPU per request from "
				+ "the service's own counter and PostgreSQL's cgroup; steal from the VM's /proc/stat");
		check.set("layouts", layouts);
		Path path = results.resolve("envcheck").resolve(started.replace(':', '-') + ".json");
		ResultFiles.write(path, check);
		layouts.properties()
			.forEach((layout) -> layout.getValue().get("steps").forEach((step) -> logStep(layout.getKey(), step)));
		return path;
	}

	// k6 on the runner's CPUs, and on those of them off the service's physical core
	private static Map<String, String> layouts(Map<String, String> sets, Map<Integer, List<Integer>> siblings) {
		String k6Cpus = Objects.requireNonNull(sets.get("k6"), "k6's CPUs");
		Set<Integer> serviceCore = Cpusets.cpus(sets.getOrDefault("api", ""))
			.stream()
			.flatMap((cpu) -> CpuTopology.coreOf(cpu, siblings).stream())
			.collect(Collectors.toSet());
		Map<String, String> layouts = new LinkedHashMap<>();
		layouts.put("runner", k6Cpus);
		layouts.put("serviceCoreAlone",
				Cpusets.cpus(k6Cpus)
					.stream()
					.filter((cpu) -> !serviceCore.contains(cpu))
					.map(String::valueOf)
					.collect(Collectors.joining(",")));
		return layouts;
	}

	// a layout's k6 CPUs, how they fall on physical cores and its steps through the rates
	private ObjectNode sweep(Instruments running, Map<String, String> sets, Map<Integer, List<Integer>> siblings,
			String k6Cpus) {
		Map<String, String> withK6 = new LinkedHashMap<>(sets);
		withK6.put("k6", k6Cpus);
		K6 k6 = new K6(running.compose().with(Map.of("BENCH_CPUS_K6", k6Cpus)), this.tools.summaries());
		ObjectNode sweep = NODES.objectNode().put("k6Cpus", k6Cpus);
		sweep.set("topology", CpuTopology.of(withK6, siblings));
		ArrayNode steps = sweep.putArray("steps");
		RATES.forEach((rate) -> steps.add(step(running, k6, k6Cpus, rate)));
		return sweep;
	}

	// one rate: a warm-up, the statistics reset, then the recorded seconds
	private ObjectNode step(Instruments running, K6 k6, String k6Cpus, int rate) {
		this.tools.log().line("envcheck: " + PythonFormat.format(rate, "g") + " req/s (k6 on CPUs " + k6Cpus + ")");
		k6.run(Traffic.of(PG_MIXED, PG_MIXED.mix(), rate), new K6.Window(0, WARMUP_S), "env-warm");
		running.services().post(ServiceHttp.API + "/reset");
		List<String> containers = Stacks.standard().containers(STACK);
		Map<String, Long> before = running.cgroups().usageUsec(containers);
		Optional<CpuTimes> vmBefore = cpuTimes(running);
		JsonNode summary = k6.run(Traffic.of(PG_MIXED, PG_MIXED.mix(), rate), new K6.Window(0, SECONDS), "env");
		Optional<CpuTimes> vmAfter = cpuTimes(running);
		Map<String, Long> after = running.cgroups().usageUsec(containers);
		JsonNode service = running.services().get(ServiceHttp.API + "/stats");
		ObjectNode measured = K6Measured.of(summary, SECONDS);
		ObjectNode step = NODES.objectNode().put("rateRps", rate);
		ObjectNode latency = step.putObject("k6");
		List.of("p50", "p95", "p99", "successRate").forEach((key) -> latency.set(key, PythonValues.get(measured, key)));
		step.set("serviceCpuMsPerRequest", PythonValues.get(service, "cpuMsPerRequest"));
		step.set("postgresCpuMsPerRequest", perRequest(before, after, service));
		ArrayNode nearTick = step.putArray("nearTimerTick");
		List.of("p95", "p99").stream().filter((key) -> nearTimerTick(measured.path(key))).forEach(nearTick::add);
		step.set("vmSteal",
				(vmBefore.isPresent() && vmAfter.isPresent())
						? PythonValues.number(PythonRound.round(vmAfter.get().stealSince(vmBefore.get()), DECIMALS))
						: PythonValues.none());
		return step;
	}

	// PostgreSQL's CPU per request over the step, None without its readings or requests
	private static JsonNode perRequest(Map<String, Long> before, Map<String, Long> after, JsonNode service) {
		Long first = before.get(STACK);
		Long last = after.get(STACK);
		double requests = PythonText.truthy(service.path("requests")) ? service.get("requests").doubleValue() : 0;
		if (first == null || last == null || last.equals(first) || requests == 0) {
			return PythonValues.none();
		}
		return PythonValues.number(PythonRound.round((last - first) / MS_PER_S / requests, DECIMALS));
	}

	private static boolean nearTimerTick(JsonNode latency) {
		return PythonText.truthy(latency) && Math.abs(latency.doubleValue() - TIMER_TICK_MS) <= NEAR_TICK_MS;
	}

	// the VM's CPU times, read inside the stack's PostgreSQL
	private static Optional<CpuTimes> cpuTimes(Instruments running) {
		return CpuTimes.of(running.compose().result("exec", "-T", "postgres", "cat", "/proc/stat").output());
	}

	private void logStep(String layout, JsonNode step) {
		this.tools.log()
			.line(String.format(Locale.ROOT,
					"%-16s %5s req/s: service %s ms, postgres %s ms per request, p95 %s, steal %s%s", layout,
					step.get("rateRps").asString(), PythonText.str(step.get("serviceCpuMsPerRequest")),
					PythonText.str(step.get("postgresCpuMsPerRequest")), PythonText.str(step.at("/k6/p95")),
					PythonText.str(step.get("vmSteal")),
					step.get("nearTimerTick").isEmpty() ? "" : " NEAR TIMER TICK"));
	}

}
