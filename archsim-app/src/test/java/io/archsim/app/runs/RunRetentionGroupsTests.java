package io.archsim.app.runs;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A seed group is pruned whole or not at all, as its summary reads every run of it: its
 * runs stay while any of them is kept, and the group goes with its last run.
 */
class RunRetentionGroupsTests extends RunRetentionTestSupport {

	@Test
	void prune_seedGroupPartlyWithinTheLimit_keepsEveryRunOfIt() {
		String project = project();
		String group = group(project, 5, 4, 3);
		run(project, 2);
		run(project, 1);

		retention().prune(NOW);

		assertThat(runsOfGroup(group)).hasSize(3);
	}

	@Test
	void prune_seedGroupPastTheLimit_deletesItsRuns() {
		String project = project();
		String group = group(project, 6, 5);
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOfGroup(group)).isEmpty();
	}

	@Test
	void prune_seedGroupPastTheLimit_deletesTheGroup() {
		String project = project();
		String group = group(project, 6, 5);
		newestRuns(project);

		retention().prune(NOW);

		assertThat(jdbc().sql("SELECT count(*) FROM run_groups WHERE id = :id")
			.param("id", group)
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void prune_seedGroupWithASignedOffRunPastTheLimit_keepsEveryRunOfIt() {
		String project = project();
		String group = group(project, 6, 5);
		signOff(runsOfGroup(group).getFirst());
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOfGroup(group)).hasSize(2);
	}

	@Test
	void prune_seedGroupWithARunAWorkerHasYetToFinish_keepsEveryRunOfIt() {
		String project = project();
		String group = group(project, 6, 5);
		jdbc().sql("UPDATE simulation_runs SET status = 'running' WHERE id = :id")
			.param("id", runsOfGroup(group).getLast())
			.update();
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOfGroup(group)).hasSize(2);
	}

}
