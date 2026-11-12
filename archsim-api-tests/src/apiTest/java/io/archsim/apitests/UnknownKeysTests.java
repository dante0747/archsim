package io.archsim.apitests;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A request body's unknown keys are ignored, as FastAPI's request models ignored them
 * (pydantic's default, {@code extra="ignore"}): a property the API does not know changes
 * nothing. A design document keeps its own models' rule and refuses its unknown keys.
 */
@ApiTest(Profile.LOCAL)
class UnknownKeysTests {

	@Test
	void createProject_withAnUnknownKey_ignoresIt(ApiClient client) {
		ApiResponse created = client.post("/projects", Json.object("name", "Extra", "colour", "blue"));

		assertThat(created.status()).as(created.text()).isEqualTo(201);
		assertThat(created.json().path("name").asString()).isEqualTo("Extra");
	}

	@Test
	void createRun_withAnUnknownKey_ignoresIt(ApiClient client) {
		String project = Json.id(Runs.createProject(client));

		ApiResponse run = client.post("/projects/" + project + "/runs", Json.object("seeds", 1, "priority", "high"));

		assertThat(run.status()).as(run.text()).isEqualTo(202);
	}

	@Test
	void export_withAnUnknownKey_ignoresIt(ApiClient client) {
		ObjectNode body = Json.object("document", Examples.starter(), "format", "mermaid", "indent", 2);

		ApiResponse exported = client.post("/export", body);

		assertThat(exported.status()).as(exported.text()).isEqualTo(200);
		assertThat(exported.json().path("format").asString()).isEqualTo("mermaid");
	}

	@Test
	void createProject_withAnUnknownKeyInItsDocument_isRefusedByTheDocumentsRule(ApiClient client) {
		ObjectNode document = Examples.starter().put("colour", "blue");

		ApiResponse created = client.post("/projects", Json.object("document", document));

		assertThat(created.status()).as(created.text()).isEqualTo(422);
		assertThat(Json.strings(created.json().path("detail").path("issues"), "path")).contains("colour");
	}

}
