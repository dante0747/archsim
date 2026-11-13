package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.calibration.Settling;
import io.archsim.benchmarks.docker.CpuTopology;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.StackLifecycle;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.load.K6;
import io.archsim.benchmarks.machine.ContentionConditions.ProbeCondition;
import io.archsim.benchmarks.machine.ContentionConditions.ServiceCondition;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.results.Timestamps;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * The contention check ({@code contention}, W27): how this machine turns CPUs into
 * capacity. pg-mixed's real service at rest, as benchmarked from a quarter of its core to
 * past it and at half of it with background work, with k6's own CPU; then a fixed unit of
 * V8 work on one CPU, alone and with its hyperthread sibling, other cores or every CPU
 * busy, and at a part of its own time. Without the probe, only the machine at rest and a
 * short ladder, about 4 minutes. Windows' counters give the cores' frequency and each
 * logical processor's busy share meanwhile. Writes
 * {@code results/envcheck/<at>-contention.json}; about 15 minutes of Docker.
 */
public final class ContentionCheck {

	/**
	 * pg-mixed's rates as benchmarked: a quarter of the event loop's core to past its
	 * capacity.
	 */
	static final List<Integer> LADDER = List.of(1250, 2500, 3700, 4400, 5000);

	/**
	 * The ladder of the service part alone: k6's CPU and the service's, short and cool.
	 */
	static final List<Integer> QUICK_LADDER = List.of(1250, 2500, 3700);

	/**
	 * pg-mixed's rates for the conditions with background work: half the event loop's
	 * core.
	 */
	static final List<Integer> CONDITION_RATES = List.of(2500);

	private static final String STACK = "postgres";

	private static final int REST_S = 15;

	/** Windows' counters' first samples, before anything is measured. */
	private static final int COUNTERS_S = 3;

	private static final String ROUNDS = "BENCH_PROBE_ROUNDS";

	private static final String DEFAULT_ROUNDS = "2";

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final CheckTools tools;

	private final Supplier<HostCounters> counters;

	private final Executor watch;

	/**
	 * The check on this machine.
	 * @param tools what it runs with
	 */
	public ContentionCheck(CheckTools tools) {
		this(tools, HostCounters::onThisMachine, K6Cpu.OWN_THREAD);
	}

	/**
	 * The check with Windows' counters and k6's watch a test gives.
	 * @param tools what it runs with
	 * @param counters what reads Windows' counters, started when the check starts
	 * @param watch what watches k6's CPU beside its runs
	 */
	ContentionCheck(CheckTools tools, Supplier<HostCounters> counters, Executor watch) {
		this.tools = tools;
		this.counters = counters;
		this.watch = watch;
	}

	/**
	 * Runs the check's parts and writes it; the probes are removed either way.
	 * @param results the results folder
	 * @param parts which parts
	 * @return the file written
	 */
	public Path run(Path results, Parts parts) {
		Instruments instruments = this.tools.instruments();
		Map<Integer, List<Integer>> siblings = CpuTopology.siblings(instruments.compose()).orElse(Map.of());
		String started = Timestamps.now(this.tools.log().clock());
		Probes probes = new Probes(instruments.compose(), instruments.ticker());
		double offsetMs = probes.vmClockOffsetMs();
		Measured measured;
		try (HostCounters host = this.counters.get()) {
			instruments.ticker().sleep(COUNTERS_S);
			measured = measure(new ContentionTools(this.tools, probes, host, this.watch), parts, offsetMs);
		}
		Path path = results.resolve("envcheck").resolve(started.replace(':', '-') + "-contention.json");
		ResultFiles.write(path, check(started, siblings, measured));
		return path;
	}

	// the service's part, then the probe's: its every-CPU conditions heat the machine (a
	// laptop throttles after about 10 minutes of it); the probes removed either way
	private Measured measure(ContentionTools contention, Parts parts, double offsetMs) {
		ArrayNode rounds = NODES.arrayNode();
		ArrayNode steps = NODES.arrayNode();
		try {
			JsonNode rest = parts.service() ? service(contention, parts, steps) : PythonValues.none();
			for (int round = 0; round < rounds(parts); round++) {
				rounds.add(round(new ProbeConditions(contention), round, offsetMs));
			}
			return new Measured(rounds, rest, steps);
		}
		finally {
			contention.probes().clear();
		}
	}

	// the stack at rest, then the ladder and the conditions; the stack stopped either way
	private JsonNode service(ContentionTools contention, Parts parts, ArrayNode steps) {
		StackLifecycle stacks = this.tools.stacks();
		Instruments running = stacks.up(STACK);
		try {
			Settling.settle(running, new K6(running.compose(), this.tools.summaries()), this.tools.log(),
					Experiments.standard().get("pg-mixed"));
			double fromMs = contention.hostMs();
			running.ticker().sleep(REST_S);
			JsonNode rest = contention.counters()
				.window(fromMs, contention.hostMs())
				.map(JsonNode.class::cast)
				.orElse(PythonValues.none());
			this.tools.log()
				.line("contention: machine at rest " + PythonText.str(PythonValues.get(rest, "busyLogicalProcessors"))
						+ " busy LPs");
			List<ServiceCondition> conditions = ContentionConditions.service();
			ServiceSteps service = new ServiceSteps(contention, running);
			(parts.probe() ? LADDER : QUICK_LADDER)
				.forEach((rate) -> steps.add(service.measure(rate, conditions.getFirst())));
			(parts.probe() ? CONDITION_RATES : List.<Integer>of())
				.forEach((rate) -> conditions.subList(1, conditions.size() - 1)
					.forEach((condition) -> steps.add(service.measure(rate, condition))));
			return rest;
		}
		finally {
			stacks.down(STACK);
		}
	}

	// every condition, in the second round in reverse order, so drift shows
	private static ArrayNode round(ProbeConditions conditions, int round, double offsetMs) {
		List<ProbeCondition> order = ContentionConditions.probe();
		ArrayNode measured = NODES.arrayNode();
		((round % 2 == 0) ? order : order.reversed())
			.forEach((condition) -> measured.add(conditions.measure(condition, offsetMs)));
		return measured;
	}

	private int rounds(Parts parts) {
		return parts.probe() ? Integer.parseInt(this.tools.environment().getOrDefault(ROUNDS, DEFAULT_ROUNDS)) : 0;
	}

	private ObjectNode check(String started, Map<Integer, List<Integer>> siblings, Measured measured) {
		ObjectNode check = NODES.objectNode().put("at", started);
		check.set("environment", this.tools.machine().get());
		ObjectNode topology = check.putObject("topology").putObject("siblings");
		new TreeMap<>(siblings).forEach((cpu, core) -> topology.putArray(String.valueOf(cpu))
			.addAll(core.stream().map(NODES::numberNode).map(JsonNode.class::cast).toList()));
		check.put("how", "probe: " + ProbeConditions.SECONDS + " s of a fixed unit of V8 work (20 JSON round trips of "
				+ "a small record) on CPU " + ProbeConditions.CPU + ", every container of a condition over the same "
				+ "window; burners run the same work. Service: pg-mixed, 5 s warm-up then " + ServiceSteps.SECONDS
				+ " s recorded, burners at nice 19 on CPUs the stack uses. Host: Windows' Processor Information and "
				+ "Hyper-V logical processor counters, 1 s samples");
		check.set("probeRounds", measured.rounds());
		check.set("serviceAtRest", measured.rest());
		check.set("service", measured.steps());
		return check;
	}

	/**
	 * What the check measured.
	 *
	 * @param rounds the probe's rounds
	 * @param rest the machine at rest, as Windows saw it; None without the service's part
	 * @param steps the service's steps
	 */
	private record Measured(ArrayNode rounds, JsonNode rest, ArrayNode steps) {

	}

	/**
	 * Which of the check's parts run.
	 */
	public enum Parts {

		/** The service's part, then the probe's. */
		BOTH,

		/** The machine at rest and the service at three rates only. */
		SERVICE_ONLY,

		/** The probe's part only. */
		PROBE_ONLY;

		boolean service() {
			return this != PROBE_ONLY;
		}

		boolean probe() {
			return this != SERVICE_ONLY;
		}

	}

}
