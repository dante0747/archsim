package io.archsim.benchmarks.machine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.machine.ContentionConditions.ProbeCondition;
import io.archsim.benchmarks.machine.Probes.Probe;
import io.archsim.benchmarks.parallel.InParallel;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonText;

/**
 * One probe condition: the probe on its CPU and a burner (the same work) on each CPU the
 * condition names at its duty, every container over the same window.
 */
final class ProbeConditions {

	/** The probe's CPU. */
	static final int CPU = 2;

	/** The probe's seconds of work. */
	static final int SECONDS = 12;

	/** Every container of a condition starts its window this long after launch. */
	private static final double LEAD_MS = 5000;

	private static final double MS_PER_S = 1000.0;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final ContentionTools tools;

	/**
	 * Probe conditions run with a contention check's tools.
	 * @param tools the tools
	 */
	ProbeConditions(ContentionTools tools) {
		this.tools = tools;
	}

	/**
	 * Measures a condition.
	 * @param condition the condition
	 * @param offsetMs the clock of Docker's VM minus the host's, in milliseconds
	 * @return the condition, the probe's and each burner's cost and speed, and Windows'
	 * view of the machine over the window
	 */
	ObjectNode measure(ProbeCondition condition, double offsetMs) {
		Probes probes = this.tools.probes();
		probes.clear();
		double startVm = this.tools.hostMs() + offsetMs + LEAD_MS;
		String tag = ContentionConditions.tag(condition.name());
		launch(condition, tag, startVm);
		JsonNode probe = rates(probes.collect(tag + "-probe"));
		ObjectNode burners = NODES.objectNode();
		condition.burners()
			.keySet()
			.forEach((cpu) -> burners.set(String.valueOf(cpu), rates(probes.collect(tag + "-burn" + cpu))));
		double fromMs = startVm - offsetMs;
		ObjectNode measured = NODES.objectNode().put("condition", condition.name()).put("probeCpu", CPU);
		measured.put("probeDuty", condition.duty());
		ObjectNode duties = measured.putObject("burners");
		condition.burners().forEach((cpu, duty) -> duties.put(String.valueOf(cpu), duty));
		measured.set("probe", probe);
		measured.set("burnerResults", burners);
		JsonNode host = this.tools.counters()
			.window(fromMs, fromMs + SECONDS * MS_PER_S)
			.map(JsonNode.class::cast)
			.orElse(PythonValues.none());
		measured.set("host", host);
		log(condition, probe, host);
		return measured;
	}

	// the probe and every burner at once, each starting its window at the same moment
	private void launch(ProbeCondition condition, String tag, double startVm) {
		List<Probe> launches = new ArrayList<>(
				List.of(new Probe(tag + "-probe", CPU, condition.duty(), SECONDS, startVm, false)));
		condition.burners()
			.forEach((cpu, duty) -> launches.add(new Probe(tag + "-burn" + cpu, cpu, duty, SECONDS, startVm, false)));
		InParallel.map(launches, (probe) -> {
			this.tools.probes().launch(probe);
			return probe.name();
		}, launches.size());
	}

	private void log(ProbeCondition condition, JsonNode probe, JsonNode host) {
		this.tools.tools()
			.log()
			.line(String.format(Locale.ROOT, "contention probe %-34s cpu/unit %s us, MHz %s, busy LPs %s",
					condition.name(), PythonText.str(PythonValues.get(probe, "cpuUsPerUnit")),
					PythonText.str(PythonValues.get(host, "frequencyMHz")),
					PythonText.str(PythonValues.get(host, "busyLogicalProcessors"))));
	}

	// a container's cost and speed, None when it printed none
	private static JsonNode rates(Optional<JsonNode> printed) {
		return printed.flatMap(Probes::rates).<JsonNode>map((rates) -> rates).orElse(PythonValues.none());
	}

}
