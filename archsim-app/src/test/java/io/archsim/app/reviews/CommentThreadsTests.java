package io.archsim.app.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project's comments as the asker sees them.
 */
class CommentThreadsTests extends ReviewTestSupport {

	@Test
	void list_byKind_isOnlyCommentsOnThatKind() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String onNode = comment(ada, project, "{\"anchorKind\": \"node\", \"anchorId\": \"api\", \"body\": \"A\"}");
		comment(ada, project, "{\"body\": \"B\"}");

		JsonNode listed = json(send(ada, HttpMethod.GET, PROJECTS + project + "/comments?anchorKind=node", null));

		assertThat(ids(listed)).containsExactly(onNode);
	}

	@Test
	void list_kindInAHeader_filtersNothing() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		comment(ada, project, "{\"anchorKind\": \"node\", \"anchorId\": \"api\", \"body\": \"A\"}");
		comment(ada, project, "{\"body\": \"B\"}");

		JsonNode listed = json(ada.browser(this.mvc().get().uri(PROJECTS + project + "/comments"))
			.header("anchorKind", "node")
			.exchange());

		assertThat(listed).hasSize(2);
	}

	@Test
	void list_unknownKind_isRefused() {
		SignedIn ada = signedIn("Ada");

		assertThat(send(ada, HttpMethod.GET, PROJECTS + project(ada) + "/comments?anchorKind=link", null))
			.hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].type")
			.isEqualTo("literal_error");
	}

	@Test
	void list_commentOnNodeSinceDeleted_isDetached() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		comment(ada, project, "{\"anchorKind\": \"node\", \"anchorId\": \"db\", \"body\": \"Index?\"}");
		save(project);

		JsonNode listed = json(send(ada, HttpMethod.GET, PROJECTS + project + "/comments", null));

		assertThat(listed.get(0).path("detached").asBoolean()).isTrue();
	}

	@Test
	void list_someoneElsesComment_isDeletableByAnOwnerOnly() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "editor");
		comment(bob, project, "{\"body\": \"Mine\"}");

		JsonNode seen = json(send(ada, HttpMethod.GET, PROJECTS + project + "/comments", null)).get(0);

		assertThat(seen.path("canEdit").asBoolean()).isFalse();
		assertThat(seen.path("canDelete").asBoolean()).isTrue();
		assertThat(seen.path("author").path("name").asString()).isEqualTo("Bob");
	}

}
