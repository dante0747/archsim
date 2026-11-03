package io.archsim.app.limits;

import org.junit.jupiter.api.Test;

import io.archsim.app.config.TestProperties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What each kind of workspace may use: the hosted profiles with limits on, nothing but a
 * sandbox's own limits without.
 */
class WorkspaceLimitsTests {

	private final WorkspaceLimits hosted = new WorkspaceLimits(TestProperties.with("auth-mode=oauth"));

	private final WorkspaceLimits local = new WorkspaceLimits(TestProperties.with());

	@Test
	void profile_teamWithLimitsOn_runsFourAtOnceAndThreeHundredAnHour() {
		LimitProfile team = this.hosted.profile("team");

		assertThat(team.maxConcurrentRuns()).isEqualTo(4);
		assertThat(team.runsPerHour()).isEqualTo(300);
	}

	@Test
	void profile_unknownKindWithLimitsOn_isPersonal() {
		assertThat(this.hosted.profile("galaxy")).isEqualTo(this.hosted.profile("personal"));
	}

	@Test
	void profile_teamWithoutLimits_isUnlimitedWithTenSeeds() {
		LimitProfile team = this.local.profile("team");

		assertThat(team.maxConcurrentRuns()).isNull();
		assertThat(team.maxSeeds()).isEqualTo(10);
	}

	@Test
	void profile_sandboxWithoutLimits_keepsItsOwn() {
		assertThat(this.local.profile("sandbox").maxProjects()).isEqualTo(5);
	}

	@Test
	void maxProjects_personal_isNone() {
		assertThat(this.hosted.maxProjects("personal")).isEmpty();
	}

	@Test
	void maxConcurrentRuns_workspaceWithItsOwn_isItsOwn() {
		assertThat(this.hosted.maxConcurrentRuns("team", 9)).hasValue(9);
	}

	@Test
	void runsPerHour_withoutLimits_isOnlyTheWorkspacesOwn() {
		assertThat(this.local.runsPerHour("sandbox", null)).isEmpty();
	}

	@Test
	void runLimits_sandbox_areTightenedByItsOwn() {
		assertThat(this.local.runLimits("sandbox")).isEqualTo(new WorkspaceLimits.RunLimits(200_000, 20.0));
	}

	@Test
	void replayLimits_teamWithoutLimits_areTheServersWithinTheCalibrationCap() {
		assertThat(this.local.replayLimits("team", 60.0)).isEqualTo(new WorkspaceLimits.RunLimits(2_000_000, 60.0));
	}

	@Test
	void replayLimits_hostedTeam_replaysForAtMostItsKindsSeconds() {
		assertThat(this.hosted.replayLimits("team", 60.0).maxWallSeconds()).isEqualTo(20.0);
	}

	@Test
	void runLimits_teamWithoutLimits_areTheServers() {
		assertThat(this.local.runLimits("team")).isEqualTo(new WorkspaceLimits.RunLimits(2_000_000, 300.0));
	}

	@Test
	void maxSeeds_hostedTeam_isFive() {
		assertThat(this.hosted.maxSeeds("team")).isEqualTo(5);
	}

	@Test
	void runsPerHour_withLimitsOn_isTheKinds() {
		assertThat(this.hosted.runsPerHour("sandbox", null)).hasValue(20);
	}

}
