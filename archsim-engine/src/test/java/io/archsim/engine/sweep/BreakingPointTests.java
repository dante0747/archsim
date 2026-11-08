package io.archsim.engine.sweep;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** What the recorded sweeps cannot show: a step that fails while others run at once. */
class BreakingPointTests {

	private static final JsonNode DESIGN = JsonMapper.builder().build().readTree("""
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
			 "architecture": {"autoSize": false,
			   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
			             {"id": "api", "type": "external_api",
			              "config": {"latencyMs": {"kind": "constant", "value": 20}}}],
			   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
			 "workload": {"seed": 7, "warmupS": 1, "durationS": 6, "rps": 100}}""");

	@Test
	void sweep_stepsLongerThanARunMayLast_throwsTheStepsRefusal() {
		ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(), PriceSnapshots.shipped());
		BreakingPoint breakingPoint = new BreakingPoint(
				new Simulator(StandardComponents.registry(), PriceSnapshots.shipped()), resolver, 4);
		// the schema holds a run to an hour, so every step of 3,601 s is refused
		SweepRequest request = SweepRequest.of(null).withTiming(3601, 5);

		assertThatExceptionOfType(DocumentInvalidException.class)
			.isThrownBy(() -> breakingPoint.sweep(DESIGN, request));
	}

}
