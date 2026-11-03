package io.archsim.app.workspaces;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.HistoryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Team workspaces, made by anyone signed in and renamed and deleted by their owners.
 */
class WorkspaceChangesTests extends WorkspaceTestSupport {

	@Test
	void create_team_isOwnedByItsMaker() {
		SignedIn ada = signedIn("Ada");

		JsonNode team = created(send(ada, HttpMethod.POST, WORKSPACES, "{\"name\": \"Platform\"}"));

		assertThat(team.path("kind").asString()).isEqualTo("team");
		assertThat(team.path("role").asString()).isEqualTo("owner");
	}

	@Test
	void create_nameWithSpaces_isStripped() {
		SignedIn ada = signedIn("Ada");

		JsonNode team = created(send(ada, HttpMethod.POST, WORKSPACES, "{\"name\": \"  Platform  \"}"));

		assertThat(team.path("name").asString()).isEqualTo("Platform");
	}

	@Test
	void create_team_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");

		String team = team(ada);

		assertThat(history(team)).containsExactly(
				new HistoryEvent("workspace.create", "workspace", team, json("{\"name\": \"Team\"}"), null));
	}

	@Test
	void create_withToken_isForbidden() {
		SignedIn ada = signedIn("Ada");
		String token = token(ada.userId(), null, "write");

		MvcTestResult result = this.mvc()
			.post()
			.uri(WORKSPACES)
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"name\": \"x\"}")
			.exchange();

		assertThat(result).hasStatus(403);
	}

	@Test
	void update_byOwner_renamesIt() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);

		JsonNode renamed = json(send(ada, HttpMethod.PATCH, WORKSPACES + "/" + team, "{\"name\": \"New\"}"));

		assertThat(renamed.path("name").asString()).isEqualTo("New");
	}

	@Test
	void update_blankName_keepsTheOldName() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);

		JsonNode renamed = json(send(ada, HttpMethod.PATCH, WORKSPACES + "/" + team, "{\"name\": \"   \"}"));

		assertThat(renamed.path("name").asString()).isEqualTo("Team");
	}

	@Test
	void delete_personalWorkspace_isAConflict() {
		SignedIn ada = signedIn("Ada");

		assertThat(send(ada, HttpMethod.DELETE, WORKSPACES + "/" + ada.workspaceId(), null)).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Only team workspaces you created can be deleted.");
	}

}
