package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HistoryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Revoking share links.
 */
class ShareRevocationsTests extends ShareTestSupport {

	@Test
	void revoke_workingLink_deletesTheCopyAtOnce() {
		SignedIn ada = signedIn("Ada");
		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{}"));

		send(ada, HttpMethod.DELETE, "/api/v1/shares/" + share.path("id").asString(), null);

		assertThat(this.mvc().get().uri("/api/v1/public/shares/" + share.path("slug").asString()).exchange())
			.hasStatus(404);
		assertThat(this.jdbc()
			.sql("SELECT payload IS NULL FROM shared_reports WHERE id = :id")
			.param("id", share.path("id").asString())
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void revoke_twice_isRecordedOnce() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String id = created(share(ada, run(project, "succeeded"), "{}")).path("id").asString();

		send(ada, HttpMethod.DELETE, "/api/v1/shares/" + id, null);
		send(ada, HttpMethod.DELETE, "/api/v1/shares/" + id, null);

		assertThat(projectHistory(project)).extracting(HistoryEvent::action)
			.containsExactly("share.create", "share.revoke");
	}

	@Test
	void revoke_byViewer_isForbidden() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String id = created(share(ada, run(project(ada), "succeeded"), "{}")).path("id").asString();
		member(ada.workspaceId(), bob.userId(), "viewer");

		assertThat(send(bob, HttpMethod.DELETE, "/api/v1/shares/" + id, null)).hasStatus(403);
	}

	@Test
	void revoke_linkOfAWorkspaceElsewhere_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String id = created(share(ada, run(project(ada), "succeeded"), "{}")).path("id").asString();

		assertThat(send(signedIn("Eve"), HttpMethod.DELETE, "/api/v1/shares/" + id, null)).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Share link not found");
	}

}
