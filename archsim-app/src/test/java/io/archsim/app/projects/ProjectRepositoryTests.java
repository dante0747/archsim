package io.archsim.app.projects;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code projects} table, as the repository reads and writes it.
 */
class ProjectRepositoryTests extends ProjectTestSupport {

	@Autowired
	private ProjectRepository projects;

	@Test
	void update_fromTheStoredRevision_savesIt() {
		workspace("repo1", "team", "owner");
		storedProject("repo1p", "repo1", "local", null);
		Project stored = this.projects.find("repo1p").orElseThrow();

		boolean saved = this.projects.update(next(stored), 1);

		assertThat(saved).isTrue();
		assertThat(this.projects.find("repo1p").orElseThrow().revision()).isEqualTo(2);
	}

	@Test
	void update_fromAnOlderRevision_changesNothing() {
		workspace("repo2", "team", "owner");
		storedProject("repo2p", "repo2", "local", null);
		Project stored = this.projects.find("repo2p").orElseThrow();
		this.projects.update(next(stored), 1);

		boolean saved = this.projects.update(next(stored), 1);

		assertThat(saved).isFalse();
	}

	@Test
	void uneditedCopy_ofAnotherExample_isNone() {
		workspace("repo3", "team", "owner");
		storedProject("repo3p", "repo3", "local", "queueing-lab");

		assertThat(this.projects.uneditedCopy("repo3", "local", "cache-aside-starter")).isEmpty();
	}

	@Test
	void uneditedCopy_ofTheExample_isIt() {
		workspace("repo4", "team", "owner");
		storedProject("repo4p", "repo4", "local", "queueing-lab");

		assertThat(this.projects.uneditedCopy("repo4", "local", "queueing-lab")).map(Project::id).contains("repo4p");
	}

	@Test
	void count_workspace_isItsProjects() {
		workspace("repo5", "team", "owner");
		storedProject("repo5a", "repo5", "local", null);
		storedProject("repo5b", "repo5", "local", null);

		assertThat(this.projects.count("repo5")).isEqualTo(2);
	}

	private static Project next(Project stored) {
		return new Project(stored.id(), stored.workspaceId(), stored.createdBy(), stored.name(), stored.description(),
				stored.document(), stored.revision() + 1, stored.sourceExample(), stored.createdAt(), Instant.now());
	}

}
