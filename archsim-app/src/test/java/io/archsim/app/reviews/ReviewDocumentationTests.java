package io.archsim.app.reviews;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The OpenAPI document describes the comment list's and the history's query parameters as
 * FastAPI did: one value each, of the type, bounds and length the endpoint takes.
 */
class ReviewDocumentationTests extends HostedTestSupport {

	private static final String COMMENTS = "/api/v1/projects/{projectId}/comments";

	private static final String HISTORY = "/api/v1/projects/{projectId}/activity";

	@Test
	void comments_anchorKind_isOneOfFourWords() {
		JsonNode kind = parameter(COMMENTS, "anchorKind");

		assertThat(kind.path("in").asString()).isEqualTo("query");
		assertThat(kind.path("schema").path("enum")).containsExactly(json("\"node\""), json("\"edge\""),
				json("\"project\""), json("\"run\""));
	}

	@Test
	void comments_ids_areStringsOfTheirLength() {
		assertThat(parameter(COMMENTS, "anchorId").path("schema").path("maxLength").asInt()).isEqualTo(64);
		assertThat(parameter(COMMENTS, "runId").path("schema").path("maxLength").asInt()).isEqualTo(32);
	}

	@Test
	void comments_query_isNotDocumentedAsOneObject() {
		assertThat(json(this.mvc().get().uri("/api/v1/openapi.json").exchange().getResponse().getContentAsByteArray())
			.path("paths")
			.path(COMMENTS)
			.path("get")
			.path("parameters")
			.valueStream()
			.map((parameter) -> parameter.path("name").asString())).doesNotContain("query");
	}

	@Test
	void history_before_isAnIntegerOfAtLeastOne() {
		JsonNode before = parameter(HISTORY, "before");

		assertThat(before.path("schema").path("type").asString()).isEqualTo("integer");
		assertThat(before.path("schema").path("minimum").asInt()).isOne();
	}

	@Test
	void history_limit_isFromOneTo200AndFiftyUnlessGiven() {
		JsonNode limit = parameter(HISTORY, "limit").path("schema");

		assertThat(limit.path("type").asString()).isEqualTo("integer");
		assertThat(limit.path("minimum").asInt()).isOne();
		assertThat(limit.path("maximum").asInt()).isEqualTo(200);
		assertThat(limit.path("default").asInt()).isEqualTo(50);
	}

	private JsonNode parameter(String path, String name) {
		JsonNode document = json(
				this.mvc().get().uri("/api/v1/openapi.json").exchange().getResponse().getContentAsByteArray());
		return document.path("paths")
			.path(path)
			.path("get")
			.path("parameters")
			.valueStream()
			.filter((parameter) -> name.equals(parameter.path("name").asString()))
			.findFirst()
			.orElseThrow(() -> new AssertionError(path + " documents no " + name));
	}

	private static JsonNode json(byte[] content) {
		return json(new String(content, StandardCharsets.UTF_8));
	}

}
