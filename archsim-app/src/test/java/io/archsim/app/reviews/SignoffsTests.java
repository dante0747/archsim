package io.archsim.app.reviews;

import java.sql.Timestamp;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import io.archsim.app.support.SameInstant;

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
	void ofRun_signoffsAtTheSameInstant_highestIdFirst() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "succeeded");
		List<String> lowestFirst = SameInstant.storeThree((id) -> storeSignoffAtNine(id, run, ada.userId()));

		JsonNode listed = json(send(ada, HttpMethod.GET, "/api/v1/runs/" + run + "/signoffs", null));

		assertThat(ids(listed)).containsExactlyElementsOf(lowestFirst.reversed());
	}

	@Test
	void ofProject_signoffsAtTheSameInstant_highestIdFirst() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String run = run(project, "succeeded");
		List<String> lowestFirst = SameInstant.storeThree((id) -> storeSignoffAtNine(id, run, ada.userId()));

		JsonNode listed = json(send(ada, HttpMethod.GET, PROJECTS + project + "/signoffs", null));

		assertThat(ids(listed)).containsExactlyElementsOf(lowestFirst.reversed());
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

	private void storeSignoffAtNine(String id, String runId, String reviewerId) {
		this.jdbc()
			.sql("""
					INSERT INTO review_signoffs (id, project_id, run_id, project_revision, input_digest, result_digest,
					                             reviewer_id, decision, created_at)
					SELECT :id, r.project_id, r.id, 1, r.input_digest, r.result_digest, :reviewer, 'approved', :at
					FROM simulation_runs r WHERE r.id = :run""")
			.param("id", id)
			.param("run", runId)
			.param("reviewer", reviewerId)
			.param("at", Timestamp.from(SameInstant.NINE))
			.update();
	}

}
