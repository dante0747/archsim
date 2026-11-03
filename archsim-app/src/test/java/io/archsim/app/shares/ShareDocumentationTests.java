package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document describes the public share page's query as FastAPI did.
 */
class ShareDocumentationTests extends HostedTestSupport {

	@Test
	void open_view_isABooleanThatIsTrueUnlessGiven() {
		JsonNode view = documentedParameter("/api/v1/public/shares/{slug}", "get", "view").path("schema");

		assertThat(view.path("type").asString()).isEqualTo("boolean");
		assertThat(view.path("default").asBoolean()).isTrue();
	}

}
