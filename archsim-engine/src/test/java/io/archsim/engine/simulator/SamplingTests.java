package io.archsim.engine.simulator;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.simulation.SteadyState;
import io.archsim.model.DesignDocuments;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What a quick check's report says where no recorded case reaches: a sample with a series
 * still growing, and one too short to judge, which a sample of at least ten seconds never
 * is.
 */
class SamplingTests {

	private static final Workload WORKLOAD = DesignDocuments.load(JsonMapper.builder().build().readTree("""
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
			 "architecture": {"nodes": [], "edges": []},
			 "workload": {"rps": 5000, "warmupS": 2, "durationS": 30}}""")).workload();

	@Test
	void wholeInstead_aSampleWithAQueueStillGrowing_namesTheSeries() {
		JsonNode mode = Sampling.wholeInstead(WORKLOAD, 12,
				new SteadyState.Judged(0.01, 0.99, 0.05, List.of("svc.queueLength")));

		assertThat(mode.get("checks").get("growing")).hasToString("[\"svc.queueLength\"]");
	}

	@Test
	void wholeInstead_aSampleTooShortToJudge_saysWhy() {
		JsonNode mode = Sampling.wholeInstead(WORKLOAD, 12, new SteadyState.Unjudged("too few time buckets to judge"));

		assertThat(mode.get("checks")).hasToString("{\"reason\":\"too few time buckets to judge\"}");
	}

}
