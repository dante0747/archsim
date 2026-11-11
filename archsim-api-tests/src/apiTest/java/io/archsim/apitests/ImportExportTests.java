package io.archsim.apitests;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code POST /export} (docker-compose, Kubernetes, Mermaid) and {@code POST /import}
 * (docker-compose, Kubernetes, Terraform to a design, with the import report). From
 * {@code
 * services/api/tests/test_api_export.py} and {@code test_api_import.py}.
 */
@ApiTest(Profile.LOCAL)
class ImportExportTests {

	@Test
	void export_toEachFormat_returnsTextOrIssues(ApiClient client) {
		String[][] formats = { { "compose", "docker-compose.yml", "image: postgres:16" },
				{ "k8s", "k8s.yaml", "kind: StatefulSet" }, { "mermaid", "mmd", "flowchart LR" }, };
		for (String[] format : formats) {
			ApiResponse response = client.post("/export",
					Json.object("document", Examples.starter(), "format", format[0]));
			assertThat(response.status()).as(response.text()).isEqualTo(200);
			JsonNode out = response.json();
			assertThat(out.path("format").asString()).isEqualTo(format[0]);
			assertThat(out.path("suffix").asString()).isEqualTo(format[1]);
			assertThat(out.path("text").asString()).contains(format[2]);
		}
		ObjectNode broken = Examples.starter();
		((ObjectNode) broken.path("architecture")).put("nodes", "nope");
		ApiResponse response = client.post("/export", Json.object("document", broken, "format", "compose"));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.json().path("detail").path("issues").isEmpty()).isFalse();
	}

	@Test
	void import_composeOrExport_reportsGuessesAndRoundTrips(ApiClient client) {
		String text = Examples.text(Examples.fixture("import/voting-compose.yml"));
		ApiResponse response = client.post("/import", Json.object("format", "compose", "text", text, "name", "Voting"));
		assertThat(response.status()).as(response.text()).isEqualTo(200);
		JsonNode out = response.json();
		assertThat(out.path("document").path("metadata").path("name").asString()).isEqualTo("Voting");
		assertThat(out.path("report").path("guessed").isEmpty()).isFalse();
		assertThat(out.path("report").path("mapped").isEmpty()).isFalse();
		assertThat(out.path("document").path("provenance").path("nodes/vote/replicas").path("kind").asString())
			.isEqualTo("assumed");

		JsonNode exported = client.post("/export", Json.object("document", Examples.starter(), "format", "k8s")).json();
		JsonNode back = client.post("/import", Json.object("format", "k8s", "text", exported.path("text"))).json();
		assertThat(Json.strings(back.path("document").path("architecture").path("nodes"), "id"))
			.containsExactlyInAnyOrderElementsOf(
					Json.strings(Examples.starter().path("architecture").path("nodes"), "id"));
		assertThat(back.path("report").path("guessed").isEmpty()).isTrue();
	}

	@Test
	void import_withWrongText_saysWhatItExpected(ApiClient client) {
		ApiResponse response = client.post("/import", Json.object("format", "terraform", "text", "not json"));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("terraform show -json");
		response = client.post("/import", Json.object("format", "compose", "text", "[1, 2]"));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("services");
	}

	static Stream<Arguments> malformed() {
		return Stream.of(Arguments.of("compose", "services: ["), // not YAML
				Arguments.of("compose", "services:\n  a: just-a-string\n"),
				Arguments.of("compose", "services:\n  a:\n    deploy: [1]\n"),
				Arguments.of("k8s", "kind: Deployment\nmetadata: just-a-string\n"),
				Arguments.of("k8s", "kind: Service\nmetadata: {name: s}\nspec: [1]\n"), Arguments.of("terraform", "[]"),
				Arguments.of("terraform", "{\"values\": {\"root_module\": {\"resources\": [1]}}}"),
				Arguments.of("terraform", "[".repeat(100_000) + "]".repeat(100_000)));
	}

	@ParameterizedTest
	@MethodSource("malformed")
	void import_withMalformedInput_is422Not500(String format, String text, ApiServer server) {
		ApiResponse response = server.client().post("/import", Json.object("format", format, "text", text));
		assertThat(response.status()).as(response.text()).isEqualTo(422);
		assertThat(response.message()).startsWith("Cannot import:");
	}

}
