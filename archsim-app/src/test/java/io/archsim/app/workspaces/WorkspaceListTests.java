package io.archsim.app.workspaces;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * {@code GET /workspaces}: the workspaces someone belongs to, with their role and the
 * limits that apply in each.
 */
class WorkspaceListTests extends HostedTestSupport {

	private static final String WORKSPACES = "/api/v1/workspaces";

	@Test
	void list_signedIn_isPersonalFirstThenByNameWithoutCase() {
		SignedIn ada = signedIn("Ada");
		team("beta", ada.userId(), "viewer");
		team("Alpha", ada.userId(), "editor");

		assertThat(ada.cookie(this.mvc().get().uri(WORKSPACES))).hasStatusOk()
			.bodyJson()
			.extractingPath("$[*].name")
			.asArray()
			.containsExactly("Ada's workspace", "Alpha", "beta");
	}

	@Test
	void list_team_showsTheRoleAndTheKindsLimits() {
		SignedIn ada = signedIn("Ada");
		team("Gamma", ada.userId(), "editor");

		assertThat(ada.cookie(this.mvc().get().uri(WORKSPACES))).bodyJson()
			.extractingPath("$[1]")
			.asMap()
			.contains(entry("name", "Gamma"), entry("kind", "team"), entry("role", "editor"),
					entry("maxConcurrentRuns", 4), entry("maxRunsPerHour", 300));
	}

	@Test
	void list_withReadToken_isViewerEverywhere() {
		SignedIn ada = signedIn("Ada");
		team("Delta", ada.userId(), "owner");
		String token = token(ada.userId(), null, "read");

		assertThat(this.mvc().get().uri(WORKSPACES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).bodyJson()
			.extractingPath("$[*].role")
			.asArray()
			.containsOnly("viewer");
	}

	@Test
	void list_withWorkspaceToken_isOnlyThatWorkspace() {
		SignedIn ada = signedIn("Ada");
		String epsilon = team("Epsilon", ada.userId(), "owner");
		String token = token(ada.userId(), epsilon, "write");

		assertThat(this.mvc().get().uri(WORKSPACES).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).bodyJson()
			.extractingPath("$[*].id")
			.asArray()
			.containsExactly(epsilon);
	}

	private String team(String name, String userId, String role) {
		String id = newId();
		this.jdbc()
			.sql("INSERT INTO workspaces (id, name, kind, created_at) VALUES (:id, :name, 'team', now())")
			.param("id", id)
			.param("name", name)
			.update();
		member(id, userId, role);
		return id;
	}

}
