package io.archsim.app.shares;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * "Open a copy": a new project from the design a share link shows.
 */
class ShareCopiesTests extends ShareTestSupport {

	@Test
	void copy_workingLink_makesAProjectNamedAfterTheSharedOne() {
		String slug = sharedRun(signedIn("Ada"));
		SignedIn bob = signedIn("Bob");

		JsonNode project = created(
				send(bob, HttpMethod.POST, "/api/v1/projects/from-share", "{\"slug\": \"" + slug + "\"}"));

		assertThat(project.path("name").asString()).isEqualTo("Shop (copy)");
	}

	@Test
	void copy_withName_isNamedSo() {
		String slug = sharedRun(signedIn("Ada"));
		SignedIn bob = signedIn("Bob");

		JsonNode project = created(send(bob, HttpMethod.POST, "/api/v1/projects/from-share",
				"{\"slug\": \"" + slug + "\", \"name\": \"Mine\"}"));

		assertThat(project.path("name").asString()).isEqualTo("Mine");
	}

	@Test
	void copy_revokedLink_isNotFound() {
		SignedIn ada = signedIn("Ada");
		JsonNode share = created(share(ada, run(project(ada), "succeeded"), "{}"));
		send(ada, HttpMethod.DELETE, "/api/v1/shares/" + share.path("id").asString(), null);

		assertThat(send(ada, HttpMethod.POST, "/api/v1/projects/from-share",
				"{\"slug\": \"" + share.path("slug").asString() + "\"}"))
			.hasStatus(404);
	}

	@Test
	void copy_linkWithoutADesign_isNotFound() {
		String slug = sharedRun(signedIn("Ada"));
		withoutDesign(slug);

		assertThat(
				send(signedIn("Bob"), HttpMethod.POST, "/api/v1/projects/from-share", "{\"slug\": \"" + slug + "\"}"))
			.hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("A design in this share link was not found");
	}

	@Test
	void copy_linkThePythonApiRevoked_isNotFound() {
		String slug = sharedRun(signedIn("Ada"));
		revokedByPython(slug);

		assertThat(
				send(signedIn("Bob"), HttpMethod.POST, "/api/v1/projects/from-share", "{\"slug\": \"" + slug + "\"}"))
			.hasStatus(404)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Share link not found");
	}

}
