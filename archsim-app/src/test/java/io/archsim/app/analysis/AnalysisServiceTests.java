package io.archsim.app.analysis;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.engine.scenario.ScenarioResolver;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.validation.DocumentValidator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Checking a design: resolved and validated in Java, its analyses asked of the engine
 * service.
 */
class AnalysisServiceTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final EngineService engine = mock();

	private final AnalysisService service = new AnalysisService(
			new DocumentValidator(StandardComponents.registry(), PriceSnapshots.shipped()),
			new ScenarioResolver(StandardComponents.registry(), PriceSnapshots.shipped()), this.engine, JSON);

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
		JsonNode answer = JSON.readTree(this.service.validate(new AnalysisRequest(starter(), "")).json());

		assertThat(answer.path("valid").asBoolean()).isTrue();
	}

	@Test
	void validate_aScenarioThatBreaksTheDesign_isInvalid() throws IOException {
		JsonNode answer = JSON.readTree(this.service.validate(new AnalysisRequest(withNoReplicas(), "none")).json());

		assertThat(answer.path("valid").asBoolean()).isFalse();
		assertThat(answer.path("issues").get(0).path("code").asString()).isEqualTo("node.config_invalid");
	}

	@Test
	void validate_theDesignWithoutTheScenarioThatBreaksIt_isValid() throws IOException {
		JsonNode answer = JSON.readTree(this.service.validate(new AnalysisRequest(withNoReplicas(), null)).json());

		assertThat(answer.path("valid").asBoolean()).isTrue();
		then(this.engine).shouldHaveNoInteractions();
	}

	@Test
	void validate_scenarioTheDesignDoesNotHave_isInvalidWithTheReason() throws IOException {
		JsonNode answer = JSON.readTree(this.service.validate(new AnalysisRequest(starter(), "nope")).json());

		assertThat(answer.path("valid").asBoolean()).isFalse();
		assertThat(answer.path("issues").get(0).path("code").asString()).isEqualTo("scenario.unknown");
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

	/**
	 * The starter with a scenario that runs its API on no replicas, which is not valid.
	 */
	private static ObjectNode withNoReplicas() throws IOException {
		ObjectNode design = starter();
		ObjectNode scenario = ((ArrayNode) design.path("scenarios")).addObject().put("id", "none").put("name", "None");
		ObjectNode override = scenario.putArray("overrides").addObject().put("path", "replicas").put("value", 0);
		override.putObject("target").put("kind", "node").put("id", "api");
		return design;
	}

	private static ObjectNode starter() throws IOException {
		Path root = Path.of(System.getProperty("archsim.repoRoot", ".."));
		return (ObjectNode) JSON.readTree(Files.readString(root.resolve("examples/cache-aside-starter.json")));
	}

}
