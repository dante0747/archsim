package io.archsim.app.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HistoryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Changes to comments: authors edit theirs, they or an owner delete them, and anyone in
 * the workspace resolves or reopens a thread.
 */
class CommentEditsTests extends ReviewTestSupport {

	@Test
	void edit_ownComment_changesItsTextAndMarksItEdited() {
		SignedIn ada = signedIn("Ada");
		String id = comment(ada, project(ada), "{\"body\": \"Draft\"}");

		JsonNode edited = json(send(ada, HttpMethod.PATCH, COMMENTS + id, "{\"body\": \" Final \"}"));

		assertThat(edited.path("body").asString()).isEqualTo("Final");
		assertThat(edited.path("editedAt").isNull()).isFalse();
	}

	@Test
	void edit_sameText_isNotRecorded() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String id = comment(ada, project, "{\"body\": \"Same\"}");

		JsonNode edited = json(send(ada, HttpMethod.PATCH, COMMENTS + id, "{\"body\": \"Same \"}"));

		assertThat(edited.path("editedAt").isNull()).isTrue();
		assertThat(projectHistory(project)).extracting(HistoryEvent::action).containsExactly("comment.create");
	}

	@Test
	void edit_someoneElsesComment_isForbidden() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "editor");
		String id = comment(bob, project, "{\"body\": \"Mine\"}");

		assertThat(send(ada, HttpMethod.PATCH, COMMENTS + id, "{\"body\": \"Ours\"}")).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Only its author can edit a comment.");
	}

	@Test
	void edit_commentOfAWorkspaceElsewhere_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String id = comment(ada, project(ada), "{\"body\": \"Private\"}");

		assertThat(send(signedIn("Eve"), HttpMethod.PATCH, COMMENTS + id, "{\"body\": \"Mine now\"}")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Comment '" + id + "' not found");
	}

	@Test
	void delete_byOwnerOfSomeoneElsesComment_deletesIt() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "viewer");
		String id = comment(bob, project, "{\"body\": \"Off topic\"}");

		assertThat(send(ada, HttpMethod.DELETE, COMMENTS + id, null)).hasStatus(204);
		assertThat(json(send(ada, HttpMethod.GET, PROJECTS + project + "/comments", null))).isEmpty();
	}

	@Test
	void delete_byOwnerOfSomeoneElsesComment_isRecordedAsNotTheAuthors() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "viewer");
		String id = comment(bob, project, "{\"body\": \"Off topic\"}");

		send(ada, HttpMethod.DELETE, COMMENTS + id, null);

		assertThat(projectHistory(project)).last()
			.isEqualTo(new HistoryEvent("comment.delete", "comment", id,
					json("{\"anchorKind\": \"project\", \"excerpt\": \"Off topic\", \"byAuthor\": false}"), project));
	}

	@Test
	void delete_ownComment_isRecordedAsTheAuthors() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String id = comment(ada, project, "{\"body\": \"Never mind\"}");

		send(ada, HttpMethod.DELETE, COMMENTS + id, null);

		assertThat(projectHistory(project)).last()
			.isEqualTo(new HistoryEvent("comment.delete", "comment", id,
					json("{\"anchorKind\": \"project\", \"excerpt\": \"Never mind\", \"byAuthor\": true}"), project));
	}

	@Test
	void delete_byEditorOfSomeoneElsesComment_isForbidden() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "editor");
		String id = comment(ada, project, "{\"body\": \"Keep\"}");

		assertThat(send(bob, HttpMethod.DELETE, COMMENTS + id, null)).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Only its author or a workspace owner can delete a comment.");
	}

	@Test
	void delete_threadsFirstComment_deletesItsReplies() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String first = comment(ada, project, "{\"body\": \"Thread\"}");
		comment(ada, project, "{\"parentId\": \"" + first + "\", \"body\": \"Reply\"}");

		send(ada, HttpMethod.DELETE, COMMENTS + first, null);

		assertThat(json(send(ada, HttpMethod.GET, PROJECTS + project + "/comments", null))).isEmpty();
	}

	@Test
	void resolve_replyWithoutBody_resolvesItsThread() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String first = comment(ada, project, "{\"body\": \"Thread\"}");
		String reply = comment(ada, project, "{\"parentId\": \"" + first + "\", \"body\": \"Reply\"}");

		JsonNode resolved = json(send(ada, HttpMethod.POST, COMMENTS + reply + "/resolve", null));

		assertThat(resolved.path("id").asString()).isEqualTo(first);
		assertThat(resolved.path("resolvedBy").path("id").asString()).isEqualTo(ada.userId());
	}

	@Test
	void resolve_falseOnResolvedThread_reopensIt() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String id = comment(ada, project, "{\"body\": \"Thread\"}");
		send(ada, HttpMethod.POST, COMMENTS + id + "/resolve", "{}");

		JsonNode reopened = json(send(ada, HttpMethod.POST, COMMENTS + id + "/resolve", "{\"resolved\": false}"));

		assertThat(reopened.path("resolvedAt").isNull()).isTrue();
		assertThat(projectHistory(project)).extracting(HistoryEvent::action)
			.containsExactly("comment.create", "comment.resolve", "comment.reopen");
	}

	@Test
	void resolve_resolvedThread_isNotRecordedAgain() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String id = comment(ada, project, "{\"body\": \"Thread\"}");
		send(ada, HttpMethod.POST, COMMENTS + id + "/resolve", null);

		send(ada, HttpMethod.POST, COMMENTS + id + "/resolve", "null");

		assertThat(projectHistory(project)).extracting(HistoryEvent::action)
			.containsExactly("comment.create", "comment.resolve");
	}

}
