package io.archsim.engine.simulator;

import java.util.Set;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.host.SiblingSpeeds;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.simulation.SteadyState;
import io.archsim.model.DesignDocuments;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;
import io.archsim.model.text.PythonFormat;

/**
 * A quick check's parts: the design shortened to its warm-up and sample, and what the
 * report says of the check ({@code simulationMode}), answered from the sample or
 * simulated whole instead.
 */
final class Sampling {

	private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

	private Sampling() {
	}

	/**
	 * A resolved design (which has no scenarios of its own) shortened to its warm-up and
	 * sample. A sibling speed the run's resolution wrote into a part is left out, for the
	 * sample's own resolution to write again: it is the same, and the sample's report
	 * then says why the part's vCPUs counted as they did rather than that the part sets
	 * it.
	 * @param resolved the design as resolved: the scenario applied, the seed replaced
	 * @param simulatedS the warm-up and the sample, in seconds
	 * @param partsWithWrittenSpeeds the parts whose sibling speed the resolution wrote
	 * @return the shortened design
	 */
	static ObjectNode shortened(ArchitectureDocument resolved, double simulatedS, Set<String> partsWithWrittenSpeeds) {
		ObjectNode design = DesignDocuments.toJson(resolved);
		((ObjectNode) design.get("workload")).put("durationS", simulatedS);
		SiblingSpeeds.leaveOut(design, partsWithWrittenSpeeds);
		return design;
	}

	/**
	 * A steady sample's report made the run's answer: it says so, and names the scenario
	 * the run asked for, which the shortened design no longer names.
	 * @param sample the report of the shortened design
	 * @param prepared the run as it was asked for
	 * @param simulatedS the warm-up and the sample simulated, in seconds
	 * @param steady how the sample's halves agreed
	 * @return the report, changed
	 */
	static ObjectNode answer(ObjectNode sample, PreparedRun prepared, double simulatedS, SteadyState steady) {
		sample.set("simulationMode", answered(prepared.resolved().workload(), simulatedS, steady));
		ObjectNode provenance = (ObjectNode) sample.get("provenance");
		provenance.put("scenarioId", prepared.scenarioId());
		provenance.put("scenarioName", prepared.scenarioName());
		return sample;
	}

	/**
	 * What a run simulated whole, because its sample was not steady, says of the check.
	 * @param workload the run's workload, as resolved
	 * @param simulatedS the warm-up and the sample simulated first, in seconds
	 * @param steady how the sample's halves disagreed
	 * @return the report's {@code simulationMode}
	 */
	static ObjectNode wholeInstead(Workload workload, double simulatedS, SteadyState steady) {
		ObjectNode mode = JSON.objectNode();
		mode.put("kind", "full");
		mode.put("requestedMode", "sampled");
		mode.put("requestedDurationS", workload.durationS());
		mode.put("simulatedDurationS", workload.durationS());
		mode.set("checks", checks(steady));
		mode.put("note",
				"A quick check was asked for, but the " + seconds(simulatedS - workload.warmupS())
						+ " s sample was not steady (a queue, backlog or latency was still changing), so the whole "
						+ seconds(workload.durationS()) + " s run was simulated.");
		return mode;
	}

	private static ObjectNode answered(Workload workload, double simulatedS, SteadyState steady) {
		ObjectNode mode = JSON.objectNode();
		mode.put("kind", "sampled");
		mode.put("requestedDurationS", workload.durationS());
		mode.put("simulatedDurationS", simulatedS);
		mode.set("checks", checks(steady));
		mode.put("note",
				"Answered from a steady " + seconds(simulatedS - workload.warmupS()) + " s sample after the "
						+ seconds(workload.warmupS()) + " s warm-up: the " + seconds(workload.durationS())
						+ " s run was not simulated whole. Rates, success and percentiles hold for a stationary system;"
						+ " counts and time series cover the sample.");
		return mode;
	}

	private static ObjectNode checks(SteadyState steady) {
		ObjectNode checks = JSON.objectNode();
		switch (steady) {
			case SteadyState.Judged judged -> {
				checks.put("throughputDrift", judged.throughputDrift());
				checks.put("keepsUp", judged.keepsUp());
				checks.put("p95Drift", judged.p95Drift());
				ArrayNode growing = checks.putArray("growing");
				judged.growing().forEach(growing::add);
			}
			case SteadyState.Unjudged unjudged -> checks.put("reason", unjudged.reason());
		}
		return checks;
	}

	// a time as Python's {:g} writes it: 10, 2.5
	private static String seconds(double value) {
		return PythonFormat.format(value, "g");
	}

}
