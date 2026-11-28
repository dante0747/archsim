package io.archsim.app.projects;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.archsim.app.support.SameInstant;

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
	void names_projectsInTwoWorkspaces_includeEveryName() {
		workspace("names1", "team", "owner");
		workspace("names2", "team", "owner");
		storedProject("names1p", "names1", "local", null);
		storedProject("names2p", "names2", "local", null);
		jdbc().sql("UPDATE projects SET name = 'Fifth' WHERE id = 'names1p'").update();
		jdbc().sql("UPDATE projects SET name = 'Sixth' WHERE id = 'names2p'").update();

		assertThat(this.projects.names()).contains("Fifth", "Sixth");
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
	void uneditedCopy_copiesMadeAtTheSameInstant_isTheHighestId() {
		workspace("repo6", "team", "owner");
		List<String> lowestFirst = SameInstant.storeThree((id) -> copiedAtNine(id, "repo6"));

		Optional<Project> copy = this.projects.uneditedCopy("repo6", "local", "queueing-lab");

		assertThat(copy).map(Project::id).contains(lowestFirst.getLast());
	}

	@Test
	void latestCopy_aSavedCopy_isIt() {
		workspace("repo8", "team", "owner");
		copied("repo8p", "repo8", 2, SameInstant.NINE);

		assertThat(this.projects.latestCopy("repo8", "local", "queueing-lab")).map(Project::id).contains("repo8p");
	}

	// stored second, with the lower id and revision, the copy updated last
	// comes first only by its update time
	@Test
	void latestCopy_twoCopies_isTheOneUpdatedLast() {
		workspace("repo9", "team", "owner");
		copied("repo9b", "repo9", 3, SameInstant.NINE.plusSeconds(60));
		copied("repo9a", "repo9", 2, SameInstant.NINE.plusSeconds(120));

		assertThat(this.projects.latestCopy("repo9", "local", "queueing-lab")).map(Project::id).contains("repo9a");
	}

	@Test
	void latestCopy_copiesUpdatedAtTheSameInstant_isTheHighestId() {
		workspace("repo10", "team", "owner");
		List<String> lowestFirst = SameInstant.storeThree((id) -> copiedAtNine(id, "repo10"));

		Optional<Project> copy = this.projects.latestCopy("repo10", "local", "queueing-lab");

		assertThat(copy).map(Project::id).contains(lowestFirst.getLast());
	}

	@Test
	void inWorkspace_savedAtTheSameInstant_highestIdFirst() {
		workspace("repo7", "team", "owner");
		List<String> lowestFirst = SameInstant.storeThree((id) -> copiedAtNine(id, "repo7"));

		List<Project> listed = this.projects.inWorkspace("repo7");

		assertThat(listed).extracting(Project::id).containsExactlyElementsOf(lowestFirst.reversed());
	}

	@Test
	void count_workspace_isItsProjects() {
		workspace("repo5", "team", "owner");
		storedProject("repo5a", "repo5", "local", null);
		storedProject("repo5b", "repo5", "local", null);

		assertThat(this.projects.count("repo5")).isEqualTo(2);
	}

	/**
	 * Stores the local user's copy of {@code queueing-lab}, made at nine o'clock and
	 * never saved since.
	 * @param id its id
	 * @param workspaceId its workspace
	 */
	private void copiedAtNine(String id, String workspaceId) {
		jdbc().sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
				                      source_example, created_at, updated_at)
				VALUES (:id, :workspace, 'local', 'Copy', CAST('{}' AS jsonb), '1.7', 1, 'queueing-lab', :at, :at)""")
			.param("id", id)
			.param("workspace", workspaceId)
			.param("at", Timestamp.from(SameInstant.NINE))
			.update();
	}

	/**
	 * Stores the local user's copy of {@code queueing-lab} at a revision, updated at a
	 * time.
	 * @param id its id
	 * @param workspaceId its workspace
	 * @param revision its revision: 1 for a copy never saved
	 * @param updatedAt when it was updated last
	 */
	private void copied(String id, String workspaceId, int revision, Instant updatedAt) {
		jdbc().sql("""
				INSERT INTO projects (id, workspace_id, created_by, name, document, schema_version, revision,
				                      source_example, created_at, updated_at)
				VALUES (:id, :workspace, 'local', 'Copy', CAST('{}' AS jsonb), '1.7', :revision, 'queueing-lab',
				        :created, :updated)""")
			.param("id", id)
			.param("workspace", workspaceId)
			.param("revision", revision)
			.param("created", Timestamp.from(SameInstant.NINE))
			.param("updated", Timestamp.from(updatedAt))
			.update();
	}

	private static Project next(Project stored) {
		return new Project(stored.id(), stored.workspaceId(), stored.createdBy(), stored.name(), stored.description(),
				stored.document(), stored.revision() + 1, stored.sourceExample(), stored.createdAt(), Instant.now());
	}

}
