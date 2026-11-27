package io.archsim.app.limits;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import io.archsim.app.support.HostedTestSupport;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A route that is not the engine's takes a body of up to 64 KB, refused before it is
 * read, however much more the server's own cap would allow (ADR-0108).
 */
class SmallBodiesTests extends HostedTestSupport {

	@Test
	void comment_withABodyOverSixtyFourKilobytes_isTooLarge() {
		SignedIn ada = signedIn("Ada");

		MvcTestResult refused = send(ada, HttpMethod.POST, "/api/v1/projects/" + project(ada) + "/comments",
				"{\"body\": \"" + "c".repeat(70_000) + "\"}");

		assertThat(refused).hasStatus(413)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Request body too large: this route takes 64 KB at most");
	}

	@Test
	void comment_withASmallBody_isWritten() {
		SignedIn ada = signedIn("Ada");

		assertThat(send(ada, HttpMethod.POST, "/api/v1/projects/" + project(ada) + "/comments",
				"{\"body\": \"Looks right.\"}"))
			.hasStatus(201);
	}

	private String project(SignedIn owner) {
		String id = newId();
		jdbc().sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
				                      created_at, updated_at)
				VALUES (:id, :workspace, :user, 'Shop', CAST(:document AS jsonb), '1.7', 1, now(), now())""")
			.param("id", id)
			.param("workspace", owner.workspaceId())
			.param("user", owner.userId())
			.param("document", "{\"metadata\": {\"name\": \"Shop\"}, \"architecture\": {\"nodes\": [], \"edges\": []}}")
			.update();
		return id;
	}

}
