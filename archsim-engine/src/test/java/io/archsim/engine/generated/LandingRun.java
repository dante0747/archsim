package io.archsim.engine.generated;

import java.nio.file.Path;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.Simulator;
import io.archsim.engine.slo.RunFigures;
import io.archsim.engine.slo.SloResult;
import io.archsim.engine.slo.StoredObjectives;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.Repository;

/**
 * The run the web app's landing page states figures from ("Decide", and the hero's goal):
 * a template's scenario that meets its objectives, simulated as {@code archsim run}
 * simulates it and priced with the shipped snapshot, cut to the figures the page shows,
 * the objectives they are held to and the engine's verdict.
 */
final class LandingRun {

	/**
	 * The template, under {@code examples/}; archsim-engine's build declares its file as
	 * an input of the tests, so a change here must be made there too.
	 */
	private static final String TEMPLATE = "black-friday-kubernetes";

	/** Its scenario that holds up: the checkout sized for the peak. */
	private static final String SCENARIO = "fixed-at-peak";

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private LandingRun() {
	}

	/**
	 * Simulates the scenario.
	 * @return the template and scenario, the seed, the template's objectives, whether the
	 * run meets them, the throughput, p95 latency and success rate, and the monthly cost
	 * with its currency and provider
	 * @throws IllegalStateException when the run does not meet the objectives, which the
	 * page says it does
	 */
	static ObjectNode summary() {
		JsonNode design = JSON.readTree(template().toFile());
		JsonNode objectives = design.required("objectives");
		ObjectNode report = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped()).run(design,
				RunRequest.of(SCENARIO));
		SloResult verdict = SloResult.evaluate(StoredObjectives.read(objectives),
				RunFigures.read(report.requiredAt("/summary")));
		if (!Boolean.TRUE.equals(verdict.pass())) {
			throw new IllegalStateException(TEMPLATE + "'s " + SCENARIO + " no longer meets its objectives: "
					+ verdict.text() + "; the landing page says it holds up");
		}
		ObjectNode run = JSON.createObjectNode();
		run.put("template", TEMPLATE);
		run.put("scenario", SCENARIO);
		run.set("seed", report.requiredAt("/provenance/seed"));
		run.set("objectives", objectives);
		run.put("meetsObjectives", true);
		run.set("throughputRps", report.requiredAt("/summary/throughputRps"));
		run.set("p95Ms", report.requiredAt("/summary/latencyMs/p95"));
		run.set("successRate", report.requiredAt("/summary/successRate"));
		run.set("monthlyCost", report.requiredAt("/cost/monthly"));
		run.set("currency", report.requiredAt("/cost/currency"));
		run.set("provider", report.requiredAt("/cost/pricing/provider"));
		return run;
	}

	private static Path template() {
		return Repository.root().resolve("examples").resolve(TEMPLATE + ".json");
	}

}
