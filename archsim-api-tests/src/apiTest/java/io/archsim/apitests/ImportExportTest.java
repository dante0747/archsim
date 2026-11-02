package io.archsim.apitests;

import static io.archsim.apitests.Json.object;
import static io.archsim.apitests.Json.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@code POST /export} (docker-compose, Kubernetes, Mermaid) and {@code POST /import}
 * (docker-compose, Kubernetes, Terraform to a design, with the import report). From
 * {@code
 * services/api/tests/test_api_export.py} and {@code test_api_import.py}.
 */
@ApiTest(Profile.LOCAL)
class ImportExportTest {

	@Test
	void exportFormatsAndErrors(ApiClient client) {
		String[][] formats = { { "compose", "docker-compose.yml", "image: postgres:16" },
				{ "k8s", "k8s.yaml", "kind: StatefulSet" }, { "mermaid", "mmd", "flowchart LR" }, };
		for (String[] f : formats) {
			ApiResponse res = client.post("/export", object("document", Examples.starter(), "format", f[0]));
			assertThat(res.status()).as(res.text()).isEqualTo(200);
			JsonNode out = res.json();
			assertThat(out.path("format").asString()).isEqualTo(f[0]);
			assertThat(out.path("suffix").asString()).isEqualTo(f[1]);
			assertThat(out.path("text").asString()).contains(f[2]);
		}
		ObjectNode broken = Examples.starter();
		((ObjectNode) broken.path("architecture")).put("nodes", "nope");
		ApiResponse res = client.post("/export", object("document", broken, "format", "compose"));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.json().path("detail").path("issues").isEmpty()).isFalse();
	}

	@Test
	void importReportsGuessesAndRoundTripsAnExport(ApiClient client) {
		String text = Examples.text(Examples.engineFixture("import/voting-compose.yml"));
		ApiResponse res = client.post("/import", object("format", "compose", "text", text, "name", "Voting"));
		assertThat(res.status()).as(res.text()).isEqualTo(200);
		JsonNode out = res.json();
		assertThat(out.path("document").path("metadata").path("name").asString()).isEqualTo("Voting");
		assertThat(out.path("report").path("guessed").isEmpty()).isFalse();
		assertThat(out.path("report").path("mapped").isEmpty()).isFalse();
		assertThat(out.path("document").path("provenance").path("nodes/vote/replicas").path("kind").asString())
			.isEqualTo("assumed");

		JsonNode exported = client.post("/export", object("document", Examples.starter(), "format", "k8s")).json();
		JsonNode back = client.post("/import", object("format", "k8s", "text", exported.path("text"))).json();
		assertThat(strings(back.path("document").path("architecture").path("nodes"), "id"))
			.containsExactlyInAnyOrderElementsOf(strings(Examples.starter().path("architecture").path("nodes"), "id"));
		assertThat(back.path("report").path("guessed").isEmpty()).isTrue();
	}

	@Test
	void importSaysWhatItExpected(ApiClient client) {
		ApiResponse res = client.post("/import", object("format", "terraform", "text", "not json"));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.message()).contains("terraform show -json");
		res = client.post("/import", object("format", "compose", "text", "[1, 2]"));
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.message()).contains("services");
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
	void malformedInputIsA422NotA500(String format, String text, ApiServer server) {
		ApiResponse res = server.client().post("/import", object("format", format, "text", text));
		assertThat(res.status()).as(res.text()).isEqualTo(422);
		assertThat(res.message()).startsWith("Cannot import:");
	}

}
