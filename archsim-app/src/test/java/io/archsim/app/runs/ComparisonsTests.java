package io.archsim.app.runs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

/**
 * Comparing two finished runs, through the engine.
 */
class ComparisonsTests extends RunTestSupport {

	@Test
	void compare_twoSucceededRuns_isTheEnginesComparisonNamingThem() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String first = started(start(project, "{}")).path("id").asString();
		String second = started(start(project, "{}")).path("id").asString();
		finish(first, 100.0);
		finish(second, 120.0);
		ObjectNode comparison = JSON.createObjectNode().put("verdict", "worse");
		given(engine().compare(any(), any(), eq("Current design"), eq("Current design"))).willReturn(comparison);

		JsonNode answer = json(this.mvc().get().uri("/api/v1/compare?a=" + first + "&b=" + second).exchange());

		assertThat(answer.path("verdict").asString()).isEqualTo("worse");
		assertThat(answer.path("runs").path("b").asString()).isEqualTo(second);
	}

	@Test
	void compare_unfinishedRun_isAConflict() {
		prepares(42, null, true);
		String project = project("team", "editor");
		String first = started(start(project, "{}")).path("id").asString();
		String second = started(start(project, "{}")).path("id").asString();

		assertThat(this.mvc().get().uri("/api/v1/compare?a=" + first + "&b=" + second)).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Run " + first.substring(0, 8) + " is queued; cannot compare");
	}

	@Test
	void compare_withoutRuns_isRefusedAsFastApiRefusesIt() {
		assertThat(this.mvc().get().uri("/api/v1/compare")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[*].loc[1]")
			.asArray()
			.containsExactly("a", "b");
	}

}
