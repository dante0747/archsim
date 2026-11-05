package io.archsim.app.runs;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.run.SimulationLimitException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;

/**
 * Seed groups: one input run with consecutive seeds, queued all at once or not at all,
 * and read back with the engine's summary of the finished ones.
 */
class RunGroupsTests extends RunTestSupport {

	@Test
	void create_threeSeeds_queuesConsecutiveSeedsInPlaceOrder() {
		prepares(42, null, true);
		String project = project("team", "editor");

		String group = started(start(project, "{\"seeds\": 3}")).path("groupId").asString();

		assertThat(this.jdbc()
			.sql("SELECT seed, seed_index FROM simulation_runs WHERE group_id = :group ORDER BY seed_index")
			.param("group", group)
			.query((rows, number) -> rows.getLong("seed") + "/" + rows.getInt("seed_index"))
			.list()).containsExactly("42/0", "43/1", "44/2");
	}

	@Test
	void create_threeSeeds_answersTheFirstRunLabelledWithItsPlace() {
		prepares(42, null, true);
		String project = project("team", "editor");

		JsonNode first = started(start(project, "{\"seeds\": 3, \"label\": \"Spread\"}"));

		assertThat(first.path("label").asString()).isEqualTo("Spread · seed 1 of 3");
	}

	@Test
	void create_moreSeedsThanTheWorkspaceRuns_isRefused() {
		prepares(42, null, true);
		String project = project("sandbox", "editor");

		assertThat(start(project, "{\"seeds\": 6}")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Lab can run at most 5 seeds at once.");
	}

	@Test
	void create_seedsPastTheLargest_isRefused() {
		prepares(4_294_967_294L, null, true);
		String project = project("team", "editor");

		assertThat(start(project, "{\"seeds\": 3}")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("Seeds 4,294,967,294 to 4,294,967,296 go past the largest, 4,294,967,295.");
	}

	@Test
	void create_seedGroupWhoseThirdSeedTheEngineRefuses_queuesNothing() {
		prepares(42, null, true);
		willThrow(new SimulationLimitException("Expected too many requests")).given(preparation())
			.prepare(argThat((PrepareRequest request) -> Long.valueOf(44).equals(request.seed())));
		String project = project("team", "editor");

		start(project, "{\"seeds\": 3}");

		assertThat(runCount(project)).isZero();
		assertThat(groupCount(project)).isZero();
	}

	@Test
	void create_seedGroupWhoseThirdRunCannotBeStored_storesNothing() {
		prepares(42, (seed) -> prepared(seed, (seed == 44) ? "x".repeat(121) : null, true));
		String project = project("team", "editor");

		start(project, "{\"seeds\": 3}");

		assertThat(runCount(project)).isZero();
		assertThat(groupCount(project)).isZero();
	}

	@Test
	void get_groupWithARunQueued_isRunningWithTheEnginesSummary() {
		prepares(42, null, true);
		String first = started(start(project("team", "editor"), "{\"seeds\": 2}")).path("id").asString();
		finish(first, 100.0);
		given(engine().summarizeSeeds(any(), any())).willReturn(JSON.createObjectNode().put("runs", 2));

		JsonNode group = json(this.mvc().get().uri("/api/v1/run-groups/" + groupOf(first)).exchange());

		assertThat(group.path("status").asString()).isEqualTo("running");
		assertThat(group.path("summary")).isEqualTo(JSON.valueToTree(Map.of("runs", 2)));
	}

	@Test
	void get_groupWhoseRunsAllSucceeded_succeeded() {
		assertThat(groupStatus("succeeded", "succeeded")).isEqualTo("succeeded");
	}

	@Test
	void get_groupWithSomeRunsFailed_isPartial() {
		assertThat(groupStatus("succeeded", "failed")).isEqualTo("partial");
	}

	@Test
	void get_groupWithNoRunSucceeded_failed() {
		assertThat(groupStatus("failed", "cancelled")).isEqualTo("failed");
	}

	/**
	 * Starts a two-run group, leaves its runs as workers would, and reads its status.
	 * @param first how the first run ended
	 * @param second how the second run ended
	 * @return the group's status
	 */
	private String groupStatus(String first, String second) {
		prepares(42, null, true);
		given(engine().summarizeSeeds(any(), any())).willReturn(JSON.createObjectNode());
		String run = started(start(project("team", "editor"), "{\"seeds\": 2}")).path("id").asString();
		String group = groupOf(run);
		List<String> runs = this.jdbc()
			.sql("SELECT id FROM simulation_runs WHERE group_id = :group ORDER BY seed_index")
			.param("group", group)
			.query((rows, number) -> rows.getString("id"))
			.list();
		markStatus(runs.get(0), first);
		markStatus(runs.get(1), second);
		return json(this.mvc().get().uri("/api/v1/run-groups/" + group).exchange()).path("status").asString();
	}

	private String groupOf(String runId) {
		return this.jdbc()
			.sql("SELECT group_id FROM simulation_runs WHERE id = :id")
			.param("id", runId)
			.query((rows, number) -> rows.getString("group_id"))
			.single();
	}

	private int groupCount(String projectId) {
		return this.jdbc()
			.sql("SELECT count(*) FROM run_groups WHERE project_id = :id")
			.param("id", projectId)
			.query(Integer.class)
			.single();
	}

}
