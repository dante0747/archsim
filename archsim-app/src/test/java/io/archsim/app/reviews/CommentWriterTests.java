package io.archsim.app.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HistoryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New comments: on a part, the project or a run, or a reply that joins its thread's part.
 * Rate limits are on, to see which comments count against the hour.
 */
@TestPropertySource(properties = "archsim.rate-limits=true")
class CommentWriterTests extends ReviewTestSupport {

	@Test
	void create_onNode_isOnThatNode() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);

		JsonNode comment = created(
				write(ada, project, "{\"anchorKind\": \"node\", \"anchorId\": \"api\", \"body\": \"  Too slow?  \"}"));

		assertThat(comment.path("anchorKind").asString()).isEqualTo("node");
		assertThat(comment.path("anchorId").asString()).isEqualTo("api");
		assertThat(comment.path("body").asString()).isEqualTo("Too slow?");
		assertThat(comment.path("detached").asBoolean()).isFalse();
	}

	@Test
	void create_onProject_namesNoPart() {
		SignedIn ada = signedIn("Ada");

		JsonNode comment = created(write(ada, project(ada), "{\"anchorId\": \"api\", \"body\": \"Ship it\"}"));

		assertThat(comment.path("anchorKind").asString()).isEqualTo("project");
		assertThat(comment.path("anchorId").isNull()).isTrue();
	}

	@Test
	void create_onNodeNotNamed_isRefused() {
		SignedIn ada = signedIn("Ada");

		assertThat(write(ada, project(ada), "{\"anchorKind\": \"node\", \"anchorId\": \"\", \"body\": \"Hm\"}"))
			.hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Say which node the comment is on");
	}

	@Test
	void create_onRunNamedByRunId_isOnThatRun() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String run = run(project, "succeeded");

		JsonNode comment = created(
				write(ada, project, "{\"anchorKind\": \"run\", \"runId\": \"" + run + "\", \"body\": \"Odd p95\"}"));

		assertThat(comment.path("anchorId").asString()).isEqualTo(run);
		assertThat(comment.path("runId").asString()).isEqualTo(run);
	}

	@Test
	void create_aboutRunOfAnotherProject_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String other = run(project(ada), "succeeded");

		assertThat(write(ada, project(ada), "{\"runId\": \"" + other + "\", \"body\": \"Hm\"}")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Run '" + other + "' of this project not found");
	}

	@Test
	void create_replyToAReply_joinsTheThreadAndItsPart() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String first = comment(ada, project, "{\"anchorKind\": \"edge\", \"anchorId\": \"e1\", \"body\": \"Retry?\"}");
		String reply = comment(ada, project, "{\"parentId\": \"" + first + "\", \"body\": \"Yes\"}");

		JsonNode second = created(write(ada, project, "{\"parentId\": \"" + reply + "\", \"body\": \"Done\"}"));

		assertThat(second.path("parentId").asString()).isEqualTo(first);
		assertThat(second.path("anchorKind").asString()).isEqualTo("edge");
		assertThat(second.path("anchorId").asString()).isEqualTo("e1");
	}

	@Test
	void create_replyToCommentOfAnotherProject_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String other = comment(ada, project(ada), "{\"body\": \"Elsewhere\"}");

		assertThat(write(ada, project(ada), "{\"parentId\": \"" + other + "\", \"body\": \"Hm\"}")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("The comment to reply to was not found");
	}

	@Test
	void create_onlyNoBreakSpaces_isRefused() {
		SignedIn ada = signedIn("Ada");

		assertThat(write(ada, project(ada), "{\"body\": \"\u00a0\u00a0\"}")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("A comment needs some text");
	}

	@Test
	void create_byViewer_isAllowed() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "viewer");

		assertThat(write(bob, project, "{\"body\": \"Looks fine\"}")).hasStatus(201);
	}

	@Test
	void create_withReadOnlyToken_isForbidden() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String token = token(ada.userId(), null, "read");

		MvcTestResult result = this.mvc()
			.post()
			.uri(PROJECTS + project + "/comments")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"body\": \"Hm\"}")
			.exchange();

		assertThat(result).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("This API token can only read: use a token with write scope.");
	}

	@Test
	void create_onNode_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);

		String id = comment(ada, project,
				"{\"anchorKind\": \"node\", \"anchorId\": \"api\", \"body\": \"Too\\nslow\"}");

		assertThat(projectHistory(project)).containsExactly(new HistoryEvent("comment.create", "comment", id,
				json("{\"anchorKind\": \"node\", \"anchorId\": \"api\", \"reply\": false, \"excerpt\": \"Too slow\"}"),
				project));
	}

	@Test
	void create_refused_isNotCountedAgainstTheHour() {
		SignedIn ada = signedIn("Ada");

		write(ada, project(ada), "{\"anchorKind\": \"node\", \"body\": \"Hm\"}");

		assertThat(counted(ada)).isZero();
	}

	@Test
	void create_accepted_isCountedAgainstTheHour() {
		SignedIn ada = signedIn("Ada");

		write(ada, project(ada), "{\"body\": \"Fine\"}");

		assertThat(counted(ada)).isOne();
	}

	@Test
	void create_overTheHourlyLimit_isTooManyRequests() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		useUp("user:" + ada.userId() + ":comments", 240);

		assertThat(write(ada, project, "{\"body\": \"One more\"}")).hasStatus(429)
			.containsHeader(HttpHeaders.RETRY_AFTER)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("You have written many comments this hour.");
	}

	private int counted(SignedIn author) {
		return this.jdbc()
			.sql("SELECT coalesce(sum(count), 0) FROM rate_limits WHERE key = :key")
			.param("key", "user:" + author.userId() + ":comments")
			.query(Integer.class)
			.single();
	}

	private MvcTestResult write(SignedIn author, String projectId, String body) {
		return send(author, HttpMethod.POST, PROJECTS + projectId + "/comments", body);
	}

}
