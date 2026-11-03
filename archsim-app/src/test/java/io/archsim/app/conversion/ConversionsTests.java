package io.archsim.app.conversion;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.app.engine.EngineRefusalException;
import io.archsim.app.engine.EngineService;
import io.archsim.app.web.ApiException;
import io.archsim.app.web.JsonText;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

/**
 * Exports and imports, as the engine service answers them and as the API refuses them.
 */
class ConversionsTests {

	private static final ObjectNode DESIGN = JsonMapper.builder().build().createObjectNode();

	private static final List<Issue> ISSUES = List.of(Issue.error("document.schema", "bad"));

	private final EngineService engine = mock();

	private final Conversions conversions = new Conversions(this.engine);

	@Test
	void export_design_isTheEnginesAnswer() {
		given(this.engine.export(DESIGN, "k8s")).willReturn(new JsonText("{\"format\":\"k8s\"}"));

		assertThat(this.conversions.export(new ExportRequest(DESIGN, ExportFormat.K8S)).json())
			.isEqualTo("{\"format\":\"k8s\"}");
	}

	@Test
	void export_designTheEngineCannotRead_isRefusedWithItsIssues() {
		given(this.engine.export(DESIGN, "compose")).willThrow(EngineRefusalException.invalidDesign("x", ISSUES));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.conversions.export(new ExportRequest(DESIGN, ExportFormat.COMPOSE)))
			.satisfies((refusal) -> {
				assertThat(refusal.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
				assertThat(refusal.getMessage()).isEqualTo("The document cannot be exported");
				assertThat(refusal.issues()).isEqualTo(ISSUES);
			});
	}

	@Test
	void importDesign_textOfAnotherFormat_isRefusedInTheEnginesWords() {
		given(this.engine.importDesign("terraform", "x", null))
			.willThrow(EngineRefusalException.unreadable("Cannot import: expected the JSON of terraform show -json"));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.conversions.importDesign(new ImportRequest(ImportFormat.TERRAFORM, "x", null)))
			.satisfies((refusal) -> {
				assertThat(refusal.getMessage()).isEqualTo("Cannot import: expected the JSON of terraform show -json");
				assertThat(refusal.issues()).isEmpty();
			});
	}

	@Test
	void importDesign_designThatIsNotValid_isRefusedWithItsIssues() {
		given(this.engine.importDesign("compose", "x", "Shop"))
			.willThrow(EngineRefusalException.invalidDesign("x", ISSUES));

		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> this.conversions.importDesign(new ImportRequest(ImportFormat.COMPOSE, "x", "Shop")))
			.satisfies((refusal) -> {
				assertThat(refusal.getMessage()).isEqualTo("The imported design is not valid");
				assertThat(refusal.issues()).isEqualTo(ISSUES);
			});
	}

}
