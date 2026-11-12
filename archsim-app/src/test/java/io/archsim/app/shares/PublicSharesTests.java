package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share links opened by anyone, without signing in.
 */
class PublicSharesTests extends ShareTestSupport {

	@Test
	void open_workingLink_namesTheProjectAndTheScenario() {
		JsonNode shared = json(open(sharedRun(signedIn("Ada"))));

		assertThat(shared.path("projectName").asString()).isEqualTo("Shop");
		assertThat(shared.path("scenarioName").asString()).isEqualTo("Peak");
	}

	@Test
	void open_workingLink_showsTheReportWithoutTraces() {
		JsonNode shared = json(open(sharedRun(signedIn("Ada"))));

		assertThat(shared.path("report").has("traces")).isFalse();
		assertThat(shared.path("report").path("summary")).isEqualTo(json("{\"latencyP95Ms\": 120}"));
	}

	@Test
	void open_workingLink_showsTheDesignsObjectives() {
		JsonNode shared = json(open(sharedRun(signedIn("Ada"))));

		assertThat(shared.path("objectives")).isEqualTo(json("{\"latencyP95Ms\": 200}"));
	}

	@Test
	void open_workingLink_canBeCopiedButHidesTheDesign() {
		JsonNode shared = json(open(sharedRun(signedIn("Ada"))));

		assertThat(shared.has("document")).isFalse();
		assertThat(shared.path("canCopy").asBoolean()).isTrue();
	}

	@Test
	void open_linkWithoutADesign_cannotBeCopied() {
		String slug = sharedRun(signedIn("Ada"));
		withoutDesign(slug);

		assertThat(json(open(slug)).path("canCopy").asBoolean()).isFalse();
	}

	@Test
	void open_linkThePythonApiRevoked_isNotFound() {
		String slug = sharedRun(signedIn("Ada"));
		revokedByPython(slug);

		assertThat(open(slug)).hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Share link not found");
	}

	@Test
	void open_twiceWithOnePreview_countsTwoViews() {
		SignedIn ada = signedIn("Ada");
		String slug = sharedRun(ada);
		open(slug);
		open(slug);

		this.mvc().get().uri("/api/v1/public/shares/" + slug + "?view=false").exchange();

		assertThat(this.jdbc()
			.sql("SELECT view_count FROM shared_reports WHERE slug = :slug")
			.param("slug", slug)
			.query(Integer.class)
			.single()).isEqualTo(2);
	}

	@Test
	void open_workingLink_isNeitherCachedNorIndexed() {
		String slug = sharedRun(signedIn("Ada"));

		assertThat(open(slug)).hasStatus(200)
			.hasHeader("Cache-Control", "no-store")
			.hasHeader("X-Robots-Tag", "noindex");
	}

	@Test
	void open_expiredLink_isNotFoundAndNotIndexed() {
		String slug = sharedRun(signedIn("Ada"));
		this.jdbc()
			.sql("UPDATE shared_reports SET expires_at = now() - interval '1 second' WHERE slug = :slug")
			.param("slug", slug)
			.update();

		MvcTestResult result = open(slug);

		assertThat(result).hasStatus(404).hasHeader("Cache-Control", "no-store").hasHeader("X-Robots-Tag", "noindex");
		assertThat(result).bodyJson().extractingPath("$.detail.message").isEqualTo("Share link not found");
	}

	@Test
	void open_textThatIsNoSlug_isNotFound() {
		assertThat(open("not-a-slug")).hasStatus(404);
	}

	@Test
	void open_viewWithAComma_isRefusedAsFastApiRefusesIt() {
		String slug = sharedRun(signedIn("Ada"));

		assertThat(this.mvc().get().uri("/api/v1/public/shares/" + slug + "?view=true,false")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].type")
			.isEqualTo("bool_parsing");
	}

	private MvcTestResult open(String slug) {
		return this.mvc().get().uri("/api/v1/public/shares/" + slug).exchange();
	}

}
