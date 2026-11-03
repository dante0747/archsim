package io.archsim.app.runs;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.PrepareRequest;
import io.archsim.app.engine.PreparedRun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;

/**
 * Batches: several scenarios of one design, all queued or none.
 */
class BatchesTests extends RunTestSupport {

	@Test
	void start_twoScenarios_queuesEachWithItsScenario() {
		prepares(42, "Peak", true);
		String project = project("team", "editor");

		MvcTestResult result = post("/api/v1/projects/" + project + "/runs/batch",
				"{\"scenarioIds\": [null, \"peak\"]}");

		JsonNode runs = json(result);
		assertThat(result).hasStatus(202);
		assertThat(runs.path(0).path("scenarioId").isNull()).isTrue();
		assertThat(runs.path(1).path("scenarioId").asString()).isEqualTo("peak");
	}

	@Test
	void start_designAndScenario_labelsEachAsItsScenarioIsNamed() {
		willAnswer((invocation) -> {
			PrepareRequest request = invocation.getArgument(0);
			String scenario = request.scenarioId();
			return new PreparedRun(scenario, (scenario != null) ? "Peak" : null, 42, PREPARED,
					prepared(42, null, true).quickCheck());
		}).given(engine()).prepare(any());
		String project = project("team", "editor");

		JsonNode runs = json(
				post("/api/v1/projects/" + project + "/runs/batch", "{\"scenarioIds\": [null, \"peak\"]}"));

		assertThat(runs.valueStream().map((run) -> run.path("label").asString())).containsExactly("Current design",
				"Peak");
	}

	@Test
	void start_secondScenarioTheEngineRefuses_queuesNoneAndNamesIt() {
		prepares(42, null, true);
		willThrow(EngineRefusalException.invalidDesign("Unknown scenario", List.of())).given(engine())
			.prepare(argThat((PrepareRequest request) -> "peak".equals(request.scenarioId())));
		String project = project("team", "editor");

		MvcTestResult result = post("/api/v1/projects/" + project + "/runs/batch",
				"{\"scenarioIds\": [null, \"peak\"]}");

		assertThat(result).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Cannot simulate scenario 'peak': Unknown scenario");
		assertThat(runCount(project)).isZero();
	}

}
