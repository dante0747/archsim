package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document describes the sign-in endpoints' query parameters as FastAPI did:
 * one value each, of the type and length the endpoint takes.
 */
class SignInDocumentationTests extends HostedTestSupport {

	@Test
	void login_next_isAStringOfAtMostFiveHundredCharacters() {
		JsonNode next = documentedParameter("/api/v1/auth/{provider}/login", "get", "next");

		assertThat(next.path("schema").path("type").asString()).isEqualTo("string");
		assertThat(next.path("schema").path("maxLength").asInt()).isEqualTo(500);
	}

	@Test
	void logout_everywhere_isABoolean() {
		JsonNode everywhere = documentedParameter("/api/v1/auth/logout", "post", "everywhere");

		assertThat(everywhere.path("schema").path("type").asString()).isEqualTo("boolean");
	}

	@Test
	void me_always_documentsNoParameters() {
		JsonNode document = json(this.mvc().get().uri("/api/v1/openapi.json").exchange());

		assertThat(document.path("paths").path("/api/v1/auth/me").path("get").path("parameters").isMissingNode())
			.isTrue();
	}

	@Test
	void callback_state_isAnOptionalQueryStringOfAtMostTwoHundredCharacters() {
		JsonNode state = documentedParameter("/api/v1/auth/{provider}/callback", "get", "state");

		assertThat(state.path("in").asString()).isEqualTo("query");
		assertThat(state.path("required").asBoolean(false)).isFalse();
		assertThat(state.path("schema").path("maxLength").asInt()).isEqualTo(200);
	}

	@Test
	void callback_code_isAStringOfAtMostTwoThousandCharacters() {
		JsonNode code = documentedParameter("/api/v1/auth/{provider}/callback", "get", "code");

		assertThat(code.path("schema").path("type").asString()).isEqualTo("string");
		assertThat(code.path("schema").path("maxLength").asInt()).isEqualTo(2000);
	}

	@Test
	void callback_error_isAnOptionalQueryStringOfAtMostTwoHundredCharacters() {
		JsonNode error = documentedParameter("/api/v1/auth/{provider}/callback", "get", "error");

		assertThat(error.path("in").asString()).isEqualTo("query");
		assertThat(error.path("required").asBoolean(false)).isFalse();
		assertThat(error.path("schema").path("maxLength").asInt()).isEqualTo(200);
	}

}
