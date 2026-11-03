package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The share links of a project or a workspace.
 */
class SharesTests extends ShareTestSupport {

	@Test
	void ofProject_twoLinks_newestFirstRevokedIncluded() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String run = run(project, "succeeded");
		JsonNode first = created(share(ada, run, "{}"));
		JsonNode second = created(share(ada, run, "{}"));
		send(ada, HttpMethod.DELETE, "/api/v1/shares/" + first.path("id").asString(), null);

		JsonNode listed = json(send(ada, HttpMethod.GET, "/api/v1/projects/" + project + "/shares", null));

		assertThat(ids(listed)).containsExactly(second.path("id").asString(), first.path("id").asString());
		assertThat(listed.get(1).path("status").asString()).isEqualTo("revoked");
	}

	@Test
	void ofWorkspace_byViewer_listsTheWorkspacesLinks() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String id = created(share(ada, run(project(ada), "succeeded"), "{}")).path("id").asString();
		member(ada.workspaceId(), bob.userId(), "viewer");

		JsonNode listed = json(send(bob, HttpMethod.GET, "/api/v1/shares?workspace=" + ada.workspaceId(), null));

		assertThat(ids(listed)).containsExactly(id);
	}

}
