package io.archsim.app.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-offs as the asker sees them.
 */
class SignoffsTests extends ReviewTestSupport {

	@Test
	void ofProject_afterTheDesignIsSavedAgain_isStale() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		signOff(ada, run(project, "succeeded"));
		save(project);

		JsonNode signoff = json(send(ada, HttpMethod.GET, PROJECTS + project + "/signoffs", null)).get(0);

		assertThat(signoff.path("stale").asBoolean()).isTrue();
		assertThat(signoff.path("currentRevision").asInt()).isEqualTo(2);
	}

	@Test
	void ofRun_twoSignoffs_newestFirst() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "succeeded");
		String first = signOff(ada, run);
		String second = signOff(ada, run);

		JsonNode listed = json(send(ada, HttpMethod.GET, "/api/v1/runs/" + run + "/signoffs", null));

		assertThat(ids(listed)).containsExactly(second, first);
	}

	@Test
	void ofProject_someoneElsesSignoff_cannotBeWithdrawnByTheAsker() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "viewer");
		signOff(bob, run(project, "succeeded"));

		JsonNode signoff = json(send(ada, HttpMethod.GET, PROJECTS + project + "/signoffs", null)).get(0);

		assertThat(signoff.path("canWithdraw").asBoolean()).isFalse();
		assertThat(signoff.path("reviewer").path("name").asString()).isEqualTo("Bob");
	}

	private String signOff(SignedIn reviewer, String runId) {
		return created(
				send(reviewer, HttpMethod.POST, "/api/v1/runs/" + runId + "/signoffs", "{\"decision\": \"approved\"}"))
			.path("id")
			.asString();
	}

}
