package io.archsim.app.shares;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An expired share link's copy of the run's results goes, as revoking deletes it at once:
 * the page answers {@code 404} either way. The link stays in its workspace's lists, as
 * expired.
 */
class ShareRetentionTests extends ShareTestSupport {

	@Autowired
	private ShareRetention retention;

	@Test
	void prune_expiredLink_deletesItsCopy() {
		String slug = sharedRun(signedIn("Ada"));
		expire(slug);

		this.retention.prune(Instant.now());

		assertThat(holdsACopy(slug)).isFalse();
	}

	@Test
	void prune_expiredLink_staysListedAsExpired() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		String slug = created(share(ada, run(project, "succeeded"), "{}")).path("slug").asString();
		expire(slug);

		this.retention.prune(Instant.now());

		JsonNode listed = json(send(ada, HttpMethod.GET, "/api/v1/projects/" + project + "/shares", null));
		assertThat(
				listed.valueStream().map((link) -> link.path("slug").asString() + ":" + link.path("status").asString()))
			.containsExactly(slug + ":expired");
	}

	@Test
	void prune_linkBeforeItExpires_keepsItsCopy() {
		String slug = sharedRun(signedIn("Ada"));

		this.retention.prune(Instant.now());

		assertThat(this.mvc().get().uri("/api/v1/public/shares/" + slug).exchange()).hasStatusOk();
	}

	@Test
	void prune_linkThatNeverExpires_keepsItsCopy() {
		SignedIn ada = signedIn("Ada");
		String slug = created(share(ada, run(project(ada), "succeeded"), "{\"expiresInDays\": null}")).path("slug")
			.asString();

		this.retention.prune(Instant.now().plus(Duration.ofDays(3650)));

		assertThat(holdsACopy(slug)).isTrue();
	}

	private void expire(String slug) {
		jdbc().sql("UPDATE shared_reports SET expires_at = now() - interval '1 second' WHERE slug = :slug")
			.param("slug", slug)
			.update();
	}

	private boolean holdsACopy(String slug) {
		return jdbc().sql("SELECT payload IS NOT NULL FROM shared_reports WHERE slug = :slug")
			.param("slug", slug)
			.query(Boolean.class)
			.single();
	}

}
