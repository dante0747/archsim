package io.archsim.app.reviews;

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
		JsonNode kind = documentedParameter(COMMENTS, "get", "anchorKind");

		assertThat(kind.path("in").asString()).isEqualTo("query");
		assertThat(kind.path("schema").path("enum")).containsExactly(json("\"node\""), json("\"edge\""),
				json("\"project\""), json("\"run\""));
	}

	@Test
	void comments_ids_areStringsOfTheirLength() {
		assertThat(documentedParameter(COMMENTS, "get", "anchorId").path("schema").path("maxLength").asInt())
			.isEqualTo(64);
		assertThat(documentedParameter(COMMENTS, "get", "runId").path("schema").path("maxLength").asInt())
			.isEqualTo(32);
	}

	@Test
	void comments_query_isNotDocumentedAsOneObject() {
		assertThat(json(this.mvc().get().uri("/api/v1/openapi.json").exchange()).path("paths")
			.path(COMMENTS)
			.path("get")
			.path("parameters")
			.valueStream()
			.map((parameter) -> parameter.path("name").asString())).doesNotContain("query");
	}

	@Test
	void history_before_isAnIntegerOfAtLeastOne() {
		JsonNode before = documentedParameter(HISTORY, "get", "before");

		assertThat(before.path("schema").path("type").asString()).isEqualTo("integer");
		assertThat(before.path("schema").path("minimum").asInt()).isOne();
	}

	@Test
	void history_limit_isFromOneTo200AndFiftyUnlessGiven() {
		JsonNode limit = documentedParameter(HISTORY, "get", "limit").path("schema");

		assertThat(limit.path("type").asString()).isEqualTo("integer");
		assertThat(limit.path("minimum").asInt()).isOne();
		assertThat(limit.path("maximum").asInt()).isEqualTo(200);
		assertThat(limit.path("default").asInt()).isEqualTo(50);
	}

}
