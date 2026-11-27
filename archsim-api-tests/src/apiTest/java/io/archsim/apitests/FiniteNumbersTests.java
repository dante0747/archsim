package io.archsim.apitests;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A design whose numbers are not all finite is refused where the number stands, whether
 * it is validated or carried by a run request (ADR-0103).
 */
@ApiTest(Profile.LOCAL)
class FiniteNumbersTests {

	private static final String TRACE_TIME = "workload.pattern.trace.points.1.0";

	private static final String NOT_FINITE = "Input should be a finite number";

	@Test
	void validate_aTraceTimeThatIsNotANumber_reportsItWhereItIs(ApiClient client) {
		JsonNode validation = client.post("/validate", Json.object("document", starterWithTraceTime("nan"))).json();

		assertThat(validation.path("valid").asBoolean()).isFalse();
		JsonNode issue = validation.path("issues").path(0);
		assertThat(issue.path("path").asString()).isEqualTo(TRACE_TIME);
		assertThat(issue.path("message").asString()).contains(NOT_FINITE);
	}

	@Test
	void run_aTraceTimeThatIsNotANumber_isRefusedForThatNumber(ApiClient client) {
		String project = Json.id(Runs.createProject(client));

		ApiResponse response = client.post("/projects/" + project + "/runs",
				Json.object("document", starterWithTraceTime("nan")));

		assertThat(response.status()).isEqualTo(422);
		JsonNode issue = response.json().path("detail").path("issues").path(0);
		assertThat(issue.path("path").asString()).isEqualTo(TRACE_TIME);
		assertThat(issue.path("message").asString()).contains(NOT_FINITE);
	}

	/** The starter, its traffic a trace of two points, the second at the given time. */
	private static ObjectNode starterWithTraceTime(String time) {
		ObjectNode document = Examples.starter();
		ArrayNode points = ((ObjectNode) document.get("workload")).putObject("pattern")
			.put("kind", "trace")
			.putArray("points");
		points.addArray().add(0).add(50);
		points.addArray().add(time).add(50);
		return document;
	}

}
