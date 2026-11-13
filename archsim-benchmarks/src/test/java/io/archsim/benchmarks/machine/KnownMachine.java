package io.archsim.benchmarks.machine;

import java.util.List;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.host.HostMachine;

/**
 * Contention checks of a machine that follows the engine's model exactly: four physical
 * cores of two threads, a thread sharing its core at half its speed alone, the clock at
 * three quarters with every thread busy.
 */
public final class KnownMachine {

	/** The machine the checks follow. */
	public static final HostMachine TRUTH = new HostMachine(4, 2, 0.5, 0.75, 0, 0);

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final List<Double> BUSY = List.of(1.5, 2.5, 3.5, 4.5, 5.5, 6.5, 7.5, 8.0);

	private KnownMachine() {
	}

	/**
	 * A check of the machine at rest with 0.4 busy threads, k6 using half a core at 2,500
	 * req/s.
	 * @return the check
	 */
	public static ObjectNode check() {
		return check(0.4, 0.5);
	}

	/**
	 * A check of the machine: a probe's CPU per unit of work, 30 µs alone, at each busy
	 * count, and the service as benchmarked at 2,500 and 5,000 req/s.
	 * @param rest the busy threads with the stack up and at rest
	 * @param k6Cores k6's busy cores at 2,500 req/s
	 * @return the check
	 */
	public static ObjectNode check(double rest, double k6Cores) {
		ObjectNode check = NODES.objectNode().put("at", "2026-11-01T03:00:00Z");
		ObjectNode siblings = check.putObject("topology").putObject("siblings");
		for (int cpu = 0; cpu < 8; cpu++) {
			siblings.putArray(String.valueOf(cpu)).add(cpu - cpu % 2).add(cpu - cpu % 2 + 1);
		}
		ArrayNode probe = check.putArray("probeRounds").addArray();
		BUSY.forEach((busy) -> probe.add(condition(busy)));
		check.putObject("serviceAtRest").put("busyLogicalProcessors", rest);
		ArrayNode service = check.putArray("service");
		service.addObject()
			.put("condition", "as benchmarked")
			.put("rateRps", 2500)
			.put("k6Cores", k6Cores)
			.put("serviceCores", 0.5);
		service.addObject()
			.put("condition", "as benchmarked")
			.put("rateRps", 5000)
			.put("k6Cores", 2.0)
			.put("serviceCores", 1.0);
		return check;
	}

	// the probe at a busy count: its CPU per unit slowed by the machine's speed there,
	// the clock falling a quarter from one busy thread to eight
	private static ObjectNode condition(double busy) {
		ObjectNode condition = NODES.objectNode().put("condition", "D=" + busy);
		condition.putObject("probe").put("cpuUsPerUnit", 30.0 / TRUTH.speed(busy));
		condition.putObject("host")
			.put("busyLogicalProcessors", busy)
			.put("frequencyMHz", 4000.0 * (1 - 0.25 * (busy - 1) / 7));
		return condition;
	}

}
