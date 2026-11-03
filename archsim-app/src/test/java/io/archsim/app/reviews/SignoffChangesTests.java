package io.archsim.app.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import io.archsim.app.limits.RateLimit;
import io.archsim.app.support.HistoryEvent;
import io.archsim.app.support.UsedUpLimits;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Sign-offs given on finished runs. Rate limits are on, to see the hourly one.
 */
@TestPropertySource(properties = "archsim.rate-limits=true")
class SignoffChangesTests extends ReviewTestSupport {

	@Test
	void create_onSucceededRun_appliesToTheDesignAsSavedNow() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String run = run(project, "succeeded");

		JsonNode signoff = created(signOff(ada, run, "{\"decision\": \"approved\", \"note\": \"  LGTM \"}"));

		assertThat(signoff.path("projectRevision").asInt()).isOne();
		assertThat(signoff.path("inputDigest").asString()).isEqualTo("in-digest");
		assertThat(signoff.path("resultDigest").asString()).isEqualTo("out-digest");
		assertThat(signoff.path("runLabel").asString()).isEqualTo("Baseline");
		assertThat(signoff.path("note").asString()).isEqualTo("LGTM");
	}

	@Test
	void create_blankNote_hasNone() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "succeeded");

		JsonNode signoff = created(signOff(ada, run, "{\"decision\": \"approved\", \"note\": \"   \"}"));

		assertThat(signoff.path("note").isNull()).isTrue();
	}

	@Test
	void create_onQueuedRun_isAConflict() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "queued");

		assertThat(signOff(ada, run, "{\"decision\": \"approved\"}")).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Run is queued; sign off on a finished run");
	}

	@Test
	void create_withReadOnlyToken_isForbidden() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "succeeded");

		MvcTestResult result = this.mvc()
			.post()
			.uri("/api/v1/runs/" + run + "/signoffs")
			.header(HttpHeaders.AUTHORIZATION, "Bearer " + token(ada.userId(), null, "read"))
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"decision\": \"approved\"}")
			.exchange();

		assertThat(result).hasStatus(403);
	}

	@Test
	void create_onSucceededRun_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String run = run(project, "succeeded");

		String id = created(signOff(ada, run, "{\"decision\": \"changes_requested\", \"note\": \"Add a cache\"}"))
			.path("id")
			.asString();

		assertThat(projectHistory(project)).containsExactly(new HistoryEvent("signoff.create", "signoff", id,
				json("{\"decision\": \"changes_requested\", \"runId\": \"" + run
						+ "\", \"revision\": 1, \"note\": \"Add a cache\"}"),
				project));
	}

	@Test
	void create_overTheHourlyLimit_isTooManyRequests() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "succeeded");
		UsedUpLimits.useUp(jdbc(), "user:" + ada.userId() + ":signoffs", RateLimit.SIGNOFFS_PER_USER);

		assertThat(signOff(ada, run, "{\"decision\": \"approved\"}")).hasStatus(429)
			.containsHeader(HttpHeaders.RETRY_AFTER)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("You have signed off many times this hour.");
	}

	private MvcTestResult signOff(SignedIn reviewer, String runId, String body) {
		return send(reviewer, HttpMethod.POST, "/api/v1/runs/" + runId + "/signoffs", body);
	}

}
