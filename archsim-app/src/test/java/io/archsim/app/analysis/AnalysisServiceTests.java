package io.archsim.app.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Checking a design: validated in Java, its scenarios and analyses asked of the engine.
 */
class AnalysisServiceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final EngineService engine = mock();

	private final AnalysisService service = new AnalysisService(StandardComponents.registry(), PriceSnapshots.shipped(),
			this.engine, JSON);

	@Test
	void validate_starterDesign_isValid() throws IOException {
		JsonText answer = this.service.validate(new AnalysisRequest(starter(), null));

		assertThat(JSON.readTree(answer.json()).path("valid").asBoolean()).isTrue();
	}

	@Test
	void validate_designMissingALink_namesTheIssue() throws IOException {
		ObjectNode design = starter();
		((ObjectNode) design.path("architecture")).putArray("edges");

		JsonNode answer = JSON.readTree(this.service.validate(new AnalysisRequest(design, null)).json());

		assertThat(answer.path("valid").asBoolean()).isFalse();
		assertThat(answer.path("issues").get(0).path("code").asString()).isEqualTo("port.missing_link");
	}

	@Test
	void validate_designItCannotRead_isInvalidWithTheReasons() {
		ObjectNode unreadable = JSON.createObjectNode().put("kind", "nope");

		JsonNode answer = JSON.readTree(this.service.validate(new AnalysisRequest(unreadable, null)).json());

		assertThat(answer.path("valid").asBoolean()).isFalse();
		assertThat(answer.path("issues").get(0).has("nodeId")).isTrue();
	}

	@Test
	void validate_emptyScenarioId_validatesTheDesignItself() throws IOException {
		this.service.validate(new AnalysisRequest(starter(), ""));

		then(this.engine).shouldHaveNoInteractions();
	}

	@Test
	void validate_scenario_isTheEnginesAnswer() throws IOException {
		ObjectNode design = starter();
		given(this.engine.validateScenario(design, "three-api-replicas")).willReturn(new JsonText("{\"valid\":true}"));

		JsonText answer = this.service.validate(new AnalysisRequest(design, "three-api-replicas"));

		assertThat(answer.json()).isEqualTo("{\"valid\":true}");
	}

	@Test
	void analyze_designTheEngineCannotRead_isRefusedWithItsIssues() throws IOException {
		ObjectNode design = starter();
		given(this.engine.analyze(design, null))
			.willThrow(EngineRefusalException.invalidDesign("bad", List.of(Issue.error("document.schema", "bad"))));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.service.analyze(new AnalysisRequest(design, null)))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
				assertThat(ex.getMessage()).isEqualTo("The document cannot be analyzed");
				assertThat(ex.issues()).containsExactly(Issue.error("document.schema", "bad"));
			});
	}

	private static ObjectNode starter() throws IOException {
		Path root = Path.of(System.getProperty("archsim.repoRoot", ".."));
		return (ObjectNode) JSON.readTree(Files.readString(root.resolve("examples/cache-aside-starter.json")));
	}

}
