package io.archsim.app.projects;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opening the same example twice with {@code reuseUnedited}: the asker's copy that was
 * never saved, and is still the example, is handed back; any other copy is not.
 */
class ProjectReuseTests extends ProjectTestSupport {

	private static final String OPEN_EXAMPLE = """
			{"exampleId": "queueing-lab", "reuseUnedited": true}""";

	@Test
	void create_sameExampleTwice_handsBackTheFirstCopy() {
		workspace("reuse1", "team", "owner");
		String first = created(post("/api/v1/projects?workspace=reuse1", OPEN_EXAMPLE)).path("id").asString();

		MvcTestResult again = post("/api/v1/projects?workspace=reuse1", OPEN_EXAMPLE);

		assertThat(again).hasStatusOk().bodyJson().extractingPath("$.id").isEqualTo(first);
	}

	@Test
	void create_afterTheCopyWasSaved_makesANewCopy() {
		workspace("reuse2", "team", "owner");
		String first = created(post("/api/v1/projects?workspace=reuse2", OPEN_EXAMPLE)).path("id").asString();
		put("/api/v1/projects/" + first, """
				{"description": "edited"}""");

		MvcTestResult again = post("/api/v1/projects?workspace=reuse2", OPEN_EXAMPLE);

		assertThat(again).hasStatus(201);
	}

	@Test
	void create_whereOnlySomeoneElseHasACopy_makesANewCopy() {
		workspace("reuse3", "team", "owner");
		user("someone3");
		storedProject("reuse3p", "reuse3", "someone3", "queueing-lab");

		MvcTestResult opened = post("/api/v1/projects?workspace=reuse3", OPEN_EXAMPLE);

		assertThat(opened).hasStatus(201);
	}

	@Test
	void create_whenTheCopyNoLongerEqualsTheExample_makesANewCopy() {
		workspace("reuse4", "team", "owner");
		storedProject("reuse4p", "reuse4", "local", "queueing-lab");

		MvcTestResult opened = post("/api/v1/projects?workspace=reuse4", OPEN_EXAMPLE);

		assertThat(opened).hasStatus(201);
	}

}
