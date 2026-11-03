package io.archsim.app.examples;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.app.web.ApiException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/** {@code GET /examples} and {@code GET /examples/{id}}. */
@WebMvcTest(ExampleController.class)
class ExampleControllerTests {

	@Autowired
	private MockMvcTester mvc;

	@MockitoBean
	private ExampleService examples;

	@Test
	void list_examples_answersTheirSummaries() {
		given(this.examples.summaries())
			.willReturn(List.of(new ExampleSummary("queueing-lab", "Queueing lab", null, List.of("teaching"), 3)));

		assertThat(this.mvc.get().uri("/api/v1/examples")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				[{"id": "queueing-lab", "name": "Queueing lab", "description": null, "tags": ["teaching"],
				  "scenarioCount": 3}]""");
	}

	@Test
	void get_knownExample_answersItsDesign() {
		given(this.examples.design("queueing-lab"))
			.willReturn(JsonNodeFactory.instance.objectNode().put("schemaVersion", "1.3"));

		assertThat(this.mvc.get().uri("/api/v1/examples/queueing-lab")).hasStatusOk()
			.bodyJson()
			.isEqualTo("{\"schemaVersion\": \"1.3\"}");
	}

	@Test
	void get_unknownExample_isNotFoundWithTheApisErrorBody() {
		given(this.examples.design("nope"))
			.willThrow(new ApiException(HttpStatus.NOT_FOUND, "Unknown example 'nope'", List.of()));

		assertThat(this.mvc.get().uri("/api/v1/examples/nope")).hasStatus(404)
			.bodyJson()
			.isStrictlyEqualTo("{\"detail\": {\"message\": \"Unknown example 'nope'\", \"issues\": []}}");
	}

}
