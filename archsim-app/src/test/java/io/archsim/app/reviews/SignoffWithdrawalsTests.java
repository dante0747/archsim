package io.archsim.app.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HistoryEvent;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-offs withdrawn by their reviewers.
 */
class SignoffWithdrawalsTests extends ReviewTestSupport {

	@Test
	void withdraw_ownSignoff_isRecordedAndGone() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String id = created(signOff(ada, run(project, "succeeded"), "{\"decision\": \"approved\"}")).path("id")
			.asString();

		assertThat(send(ada, HttpMethod.DELETE, "/api/v1/signoffs/" + id, null)).hasStatus(204);
		assertThat(projectHistory(project)).extracting(HistoryEvent::action)
			.containsExactly("signoff.create", "signoff.withdraw");
		assertThat(json(send(ada, HttpMethod.GET, PROJECTS + project + "/signoffs", null))).isEmpty();
	}

	@Test
	void withdraw_someoneElsesSignoff_isForbidden() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String project = project(ada);
		member(ada.workspaceId(), bob.userId(), "viewer");
		String id = created(signOff(bob, run(project, "succeeded"), "{\"decision\": \"approved\"}")).path("id")
			.asString();

		assertThat(send(ada, HttpMethod.DELETE, "/api/v1/signoffs/" + id, null)).hasStatus(403)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Only the reviewer can withdraw a sign-off.");
	}

	@Test
	void withdraw_signoffOfAWorkspaceElsewhere_isNotFound() {
		SignedIn ada = signedIn("Ada");
		String id = created(signOff(ada, run(project(ada), "succeeded"), "{\"decision\": \"approved\"}")).path("id")
			.asString();

		assertThat(send(signedIn("Eve"), HttpMethod.DELETE, "/api/v1/signoffs/" + id, null)).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Sign-off '" + id + "' not found");
	}

	private MvcTestResult signOff(SignedIn reviewer, String runId, String body) {
		return send(reviewer, HttpMethod.POST, "/api/v1/runs/" + runId + "/signoffs", body);
	}

}
