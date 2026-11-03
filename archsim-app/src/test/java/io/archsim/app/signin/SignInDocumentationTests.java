package io.archsim.app.signin;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document describes the sign-in endpoints' query parameters as FastAPI did:
 * one value each, of the type and length the endpoint takes.
 */
class SignInDocumentationTests extends HostedTestSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void login_next_isAStringOfAtMostFiveHundredCharacters() {
		JsonNode next = parameter("/api/v1/auth/{provider}/login", "get", "next");

		assertThat(next.path("schema").path("type").asString()).isEqualTo("string");
		assertThat(next.path("schema").path("maxLength").asInt()).isEqualTo(500);
	}

	@Test
	void logout_everywhere_isABoolean() {
		JsonNode everywhere = parameter("/api/v1/auth/logout", "post", "everywhere");

		assertThat(everywhere.path("schema").path("type").asString()).isEqualTo("boolean");
	}

	@Test
	void callback_query_isCodeStateAndErrorAsOptionalStrings() {
		JsonNode state = parameter("/api/v1/auth/{provider}/callback", "get", "state");

		assertThat(state.path("in").asString()).isEqualTo("query");
		assertThat(state.path("required").asBoolean(false)).isFalse();
		assertThat(state.path("schema").path("maxLength").asInt()).isEqualTo(200);
	}

	private JsonNode parameter(String path, String method, String name) {
		JsonNode document = JSON
			.readTree(this.mvc().get().uri("/api/v1/openapi.json").exchange().getResponse().getContentAsByteArray());
		return document.path("paths")
			.path(path)
			.path(method)
			.path("parameters")
			.valueStream()
			.filter((parameter) -> name.equals(parameter.path("name").asString()))
			.findFirst()
			.orElseThrow(() -> new AssertionError(path + " documents no " + name));
	}

}
