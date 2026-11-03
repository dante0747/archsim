package io.archsim.app.shares;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;

import io.archsim.app.limits.RateLimit;
import io.archsim.app.support.HistoryEvent;
import io.archsim.app.support.UsedUpLimits;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * New share links to finished runs. Rate limits are on, to see the daily one.
 */
@TestPropertySource(properties = "archsim.rate-limits=true")
class ShareLinksTests extends ShareTestSupport {

	@Test
	void create_withoutTitle_isTitledAfterTheProject() {
		SignedIn ada = signedIn("Ada");

		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{}"));

		assertThat(share.path("title").asString()).isEqualTo("Shop");
	}

	@Test
	void create_onFinishedRun_isAWorkingLinkToItsPage() {
		SignedIn ada = signedIn("Ada");

		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{}"));

		assertThat(share.path("status").asString()).isEqualTo("active");
		assertThat(share.path("path").asString()).isEqualTo("/r/" + share.path("slug").asString());
	}

	@Test
	void create_onServerWithPublicAddress_hasTheWholeLink() {
		SignedIn ada = signedIn("Ada");

		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{}"));

		assertThat(share.path("url").asString()).isEqualTo(PUBLIC_URL + "/r/" + share.path("slug").asString());
	}

	@Test
	void create_onFinishedRun_keepsItsReportVersion() {
		SignedIn ada = signedIn("Ada");

		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{}"));

		assertThat(share.path("reportVersion").asString()).isEqualTo("1.4");
	}

	@Test
	void create_withoutDays_expiresInThirty() {
		SignedIn ada = signedIn("Ada");

		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{}"));

		assertThat(Duration.between(Instant.parse(share.path("createdAt").asString()),
				Instant.parse(share.path("expiresAt").asString())))
			.isEqualTo(Duration.ofDays(30));
	}

	@Test
	void create_withNullDays_neverExpires() {
		SignedIn ada = signedIn("Ada");

		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{\"expiresInDays\": null}"));

		assertThat(share.path("expiresAt").isNull()).isTrue();
	}

	@Test
	void create_onQueuedRun_isAConflict() {
		SignedIn ada = signedIn("Ada");

		assertThat(share(ada, run(project(ada), "queued"), "{}")).hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Run is queued; only finished runs can be shared");
	}

	@Test
	void create_pastSharesPerDay_isTooManyRequests() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "succeeded");
		UsedUpLimits.useUp(jdbc(), "user:" + ada.userId() + ":shares", RateLimit.SHARES_PER_USER);

		assertThat(share(ada, run, "{}")).hasStatus(429)
			.containsHeader(HttpHeaders.RETRY_AFTER)
			.bodyJson()
			.extractingPath("$.detail.message")
			.asString()
			.startsWith("You have made many share links today.");
	}

	@Test
	void create_onQueuedRunPastTheLimit_isAConflict() {
		SignedIn ada = signedIn("Ada");
		String run = run(project(ada), "queued");
		UsedUpLimits.useUp(jdbc(), "user:" + ada.userId() + ":shares", RateLimit.SHARES_PER_USER);

		assertThat(share(ada, run, "{}")).hasStatus(409);
	}

	@Test
	void create_byViewer_isForbidden() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String run = run(project(ada), "succeeded");
		member(ada.workspaceId(), bob.userId(), "viewer");

		assertThat(share(bob, run, "{}")).hasStatus(403);
	}

	@Test
	void create_onFinishedRun_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String run = run(project, "succeeded");

		String id = created(share(ada, run, "{\"title\": \"  For the team  \"}")).path("id").asString();

		assertThat(projectHistory(project)).containsExactly(new HistoryEvent("share.create", "share", id,
				json("{\"title\": \"For the team\", \"runId\": \"" + run + "\", \"label\": \"Baseline\"}"), project));
	}

}
