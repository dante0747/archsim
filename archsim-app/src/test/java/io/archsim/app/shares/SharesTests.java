package io.archsim.app.shares;

import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.SameInstant;

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
	void ofProject_linksAtTheSameInstant_highestIdFirst() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String run = run(project, "succeeded");
		List<String> lowestFirst = SameInstant.storeThree((id) -> storeShareAtNine(id, run));

		JsonNode listed = json(send(ada, HttpMethod.GET, "/api/v1/projects/" + project + "/shares", null));

		assertThat(ids(listed)).containsExactlyElementsOf(lowestFirst.reversed());
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

	@Test
	void ofWorkspace_linksAtTheSameInstant_highestIdFirst() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "succeeded");
		List<String> lowestFirst = SameInstant.storeThree((id) -> storeShareAtNine(id, run));

		JsonNode listed = json(send(ada, HttpMethod.GET, "/api/v1/shares?workspace=" + ada.workspaceId(), null));

		assertThat(ids(listed)).containsExactlyElementsOf(lowestFirst.reversed());
	}

	private void storeShareAtNine(String id, String runId) {
		this.jdbc()
			.sql("""
					INSERT INTO shared_reports (id, slug, workspace_id, project_id, run_id, created_by, title,
					                            payload_version, report_version, result_digest, created_at)
					SELECT :id, :id, r.workspace_id, r.project_id, r.id, p.created_by, 'Shop', 1, '1.4',
					       r.result_digest, :at
					FROM simulation_runs r JOIN projects p ON p.id = r.project_id WHERE r.id = :run""")
			.param("id", id)
			.param("run", runId)
			.param("at", Timestamp.from(SameInstant.NINE))
			.update();
	}

}
