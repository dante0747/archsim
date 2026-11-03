package io.archsim.app.reviews;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The history of a project or a workspace, a page at a time.
 */
class ActivityTests extends ReviewTestSupport {

	@Test
	void ofProject_pageSmallerThanTheHistory_pointsToTheNextPage() {
		SignedIn ada = signedIn("Ada");
		String project = project(ada);
		for (int i = 0; i < 3; i++) {
			comment(ada, project, "{\"body\": \"Note " + i + "\"}");
		}

		JsonNode first = json(send(ada, HttpMethod.GET, PROJECTS + project + "/activity?limit=2", null));
		long next = first.path("nextBefore").asLong();
		JsonNode second = json(
				send(ada, HttpMethod.GET, PROJECTS + project + "/activity?limit=2&before=" + next, null));

		assertThat(first.path("items")).hasSize(2);
		assertThat(second.path("items")).hasSize(1);
		assertThat(second.path("nextBefore").isNull()).isTrue();
		assertThat(second.path("items").get(0).path("id").asLong()).isLessThan(next);
	}

	@Test
	void ofProject_limitZero_isRefused() {
		SignedIn ada = signedIn("Ada");

		assertThat(send(ada, HttpMethod.GET, PROJECTS + project(ada) + "/activity?limit=0", null)).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail[0].type")
			.isEqualTo("greater_than_equal");
	}

	@Test
	void ofWorkspace_byOwner_showsWhereChangesCameFrom() {
		SignedIn ada = signedIn("Ada");
		comment(ada, project(ada), "{\"body\": \"Hi\"}");

		JsonNode item = json(send(ada, HttpMethod.GET, "/api/v1/workspaces/" + ada.workspaceId() + "/activity", null))
			.path("items")
			.get(0);

		assertThat(item.path("ip").isNull()).isFalse();
		assertThat(item.path("actor").path("name").asString()).isEqualTo("Ada");
	}

	@Test
	void ofWorkspace_byViewer_hidesWhereChangesCameFrom() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		member(ada.workspaceId(), bob.userId(), "viewer");
		comment(ada, project(ada), "{\"body\": \"Hi\"}");

		JsonNode item = json(send(bob, HttpMethod.GET, "/api/v1/workspaces/" + ada.workspaceId() + "/activity", null))
			.path("items")
			.get(0);

		assertThat(item.path("ip").isNull()).isTrue();
	}

}
