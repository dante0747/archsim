package io.archsim.apitests;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seed groups and quick checks: {@code seeds} and {@code mode} on {@code POST
 * /projects/{id}/runs}, {@code GET /run-groups/{id}}, the seeds each workspace may run,
 * workspace scoping, and reruns that reproduce a group's run. From {@code
 * services/api/tests/test_api_run_groups.py}.
 *
 * <p>
 * Not ported: {@code test_a_rerun_of_a_quick_check_is_a_quick_check} and {@code
 * test_quick_checks_with_seeds} shrink the engine's sampling thresholds in-process (the
 * {@code
 * tiny_samples} fixture), which no server can be asked to do; without it, a design long
 * enough to qualify for a quick check takes minutes to run.
 * {@code test_seed_limits_per_workspace_kind} is ported in part (see it).
 */
@ApiTest(Profile.LOCAL)
class RunGroupsTests {

	/** En dash, as in "seeds 42–44" ({@code archsim_engine.seeds.DASH}). */
	private static final String DASH = "–";

	@Test
	void startRuns_withThreeSeeds_runOneInputWithConsecutiveSeeds(ApiClient client) {
		ObjectNode document = Examples.starter(4, 1);
		document.set("objectives", Json.object("latencyP95Ms", 10_000, "availability", 0.5));
		String project = Json.id(Runs.createProject(client, document));
		ApiResponse response = client.post("/projects/" + project + "/runs", Json.object("seeds", 3));
		assertThat(response.status()).as(response.text()).isEqualTo(202);
		JsonNode first = response.json();
		assertThat(first.path("groupId").asString("")).isNotEmpty();
		assertThat(first.path("seedIndex").asInt(-1)).isZero();
		assertThat(first.path("seed").asLong()).isEqualTo(42);
		assertThat(first.path("mode").asString()).isEqualTo("full");
		JsonNode group = waitForGroup(client, first.path("groupId").asString());
		List<JsonNode> runs = Json.items(group.path("runs"));
		assertGroupOfSeeds42To44(group, runs);
		// one stored document; each report ran (and records) its own seed
		assertEachRunHadItsOwnSeed(client, runs);
		// a single run of the same input is the group's first run
		JsonNode single = Runs.waitFor(client, Runs.startRun(client, project));
		assertThat(single.path("resultDigest")).isEqualTo(runs.get(0).path("resultDigest"));
		assertThat(single.path("groupId").isNull()).isTrue();
		assertThat(single.path("seedIndex").isNull()).isTrue();

		// rerunning a group's run reproduces it, seed included
		String third = Json.id(runs.get(2));
		JsonNode again = client.post("/runs/" + third + "/rerun").json();
		assertThat(again.path("seed").asLong()).isEqualTo(44);
		assertThat(again.path("reproducesRunId").asString()).isEqualTo(third);
		assertThat(Runs.waitFor(client, Json.id(again)).path("resultDigest"))
			.isEqualTo(runs.get(2).path("resultDigest"));
	}

	/** The group of three seeds from 42 that met its objectives, and its runs. */
	private static void assertGroupOfSeeds42To44(JsonNode group, List<JsonNode> runs) {
		assertThat(List.<Object>of(group.path("kind").asString(), group.path("baseSeed").asLong(),
				group.path("size").asInt(), group.path("status").asString()))
			.containsExactly("seeds", 42L, 3, "succeeded");
		assertThat(runs).extracting((run) -> run.path("seed").asLong()).containsExactly(42L, 43L, 44L);
		assertThat(runs).extracting((run) -> run.path("seedIndex").asInt()).containsExactly(0, 1, 2);
		assertThat(runs.get(1).path("label").asString()).isEqualTo("Current design · seed 2 of 3");
		JsonNode summary = group.path("summary");
		assertThat(List.<Object>of(summary.path("finished").asInt(), summary.path("met").asInt(),
				summary.path("verdict").asString()))
			.containsExactly(3, 3, "holds");
		assertThat(summary.path("text").asString()).startsWith("Meets the objectives in 3 of 3 runs · p95 ")
			.endsWith("· seeds 42" + DASH + "44");
		double minP95 = runs.stream()
			.mapToDouble((run) -> run.path("summary").path("p95").asDouble())
			.min()
			.orElseThrow();
		assertThat(summary.path("latencyMs").path("p95").path("min").asDouble()).isEqualTo(minP95);
	}

	/**
	 * The runs share one stored input, and each report ran (and records) its own seed.
	 */
	private static void assertEachRunHadItsOwnSeed(ApiClient client, List<JsonNode> runs) {
		List<JsonNode> reports = runs.stream()
			.map((run) -> client.get("/runs/" + Json.id(run) + "/report").json())
			.toList();
		assertThat(reports).extracting((report) -> report.path("provenance").path("seed").asLong())
			.containsExactly(42L, 43L, 44L);
		assertThat(reports).extracting((report) -> report.path("provenance").path("inputDigest").asString())
			.doesNotHaveDuplicates()
			.hasSize(3);
		List<JsonNode> inputs = runs.stream()
			.map((run) -> client.get("/runs/" + Json.id(run) + "/input").json())
			.toList();
		assertThat(inputs).allMatch((input) -> input.equals(inputs.get(0)));
		assertThat(inputs.get(0).path("workload").path("seed").asLong()).isEqualTo(42);
	}

	@Test
	void seedGroup_withObjectivesInTheInput_judgesRunsByThem(ApiClient client) {
		ObjectNode document = Examples.starter(4, 1);
		document.set("objectives", Json.object("latencyP95Ms", 0.001)); // nothing is that
																		// fast
		String project = Json.id(Runs.createProject(client, document));
		JsonNode first = client.post("/projects/" + project + "/runs", Json.object("seeds", 2)).json();
		JsonNode group = waitForGroup(client, first.path("groupId").asString());
		assertThat(group.path("summary").path("verdict").asString()).isEqualTo("fails");
		assertThat(group.path("summary").path("text").asString()).startsWith("Misses the objectives in 2 of 2 runs");
	}

	/** The ten runs are cancelled at the end: the Python test's server has no worker. */
	@Test
	void startRuns_withSeedsOutOfRange_isRefused(ApiClient client) {
		String project = Json.id(Runs.createProject(client, Examples.starter(4, 1)));
		String url = "/projects/" + project + "/runs";
		assertThat(client.post(url, Json.object("seeds", 0)).status()).isEqualTo(422);
		assertThat(client.post(url, Json.object("seeds", 11)).status()).isEqualTo(422);
		ApiResponse ten = client.post(url, Json.object("seeds", 10)); // locally, up to 10
		assertThat(ten.status()).as(ten.text()).isEqualTo(202);
		JsonNode group = client.get("/run-groups/" + ten.json().path("groupId").asString()).json();
		assertThat(group.path("runs").size()).isEqualTo(10);
		ObjectNode late = Examples.starter(4, 1);
		((ObjectNode) late.path("workload")).put("seed", (1L << 32) - 2);
		ApiResponse response = client.post(url, Json.object("seeds", 3, "document", late));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("go past the largest");
		assertThat(client.get("/run-groups/nope").status()).isEqualTo(404);
		cancel(client, Json.strings(group.path("runs"), "id"));
	}

	/**
	 * Ported in part: the Python test calls {@code limits.max_seeds} for each kind. Over
	 * HTTP, six seeds are refused in hosted personal, team and sandbox workspaces and in
	 * a local sandbox (five at most); a local team workspace's ten are in
	 * {@link #startRuns_withSeedsOutOfRange_isRefused} ({@code default} is a team
	 * workspace).
	 */
	@Test
	void startRuns_withSixSeedsInAnyKindOfWorkspace_isRefused(@On(Profile.HOSTED) ApiServer hosted, ApiServer local) {
		ApiClient ada = hosted.signedIn("ada");
		List<String> workspaces = List.of(Workspaces.first(ada), Json.id(Workspaces.create(ada, "Team")),
				Workspaces.insert(hosted.db(), "sandbox", Workspaces.userId(ada)));
		for (String workspace : workspaces) {
			assertAtMostFiveSeeds(ada, workspace);
		}
		assertAtMostFiveSeeds(local.client(), Workspaces.insert(local.db(), "sandbox", "local"));
	}

	private static void assertAtMostFiveSeeds(ApiClient client, String workspace) {
		String project = Json.id(createProject(client, workspace));
		ApiResponse response = client.post("/projects/" + project + "/runs", Json.object("seeds", 6));
		assertThat(response.status()).as(workspace + ": " + response.text()).isEqualTo(422);
		assertThat(response.message()).contains("at most 5 seeds");
	}

	@Test
	void startRuns_inHostedWorkspace_runAtMostFiveSeeds(@On(Profile.HOSTED) ApiServer hosted) {
		ApiClient ada = hosted.signedIn("ada");
		String project = Json.id(Runs.createProject(ada, Examples.starter(4, 1)));
		String url = "/projects/" + project + "/runs";
		ApiResponse response = ada.post(url, Json.object("seeds", 6));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("at most 5 seeds");
		assertThat(ada.post(url, Json.object("seeds", 5)).status()).isEqualTo(202);
		JsonNode runs = ada.get(url).json();
		assertThat(runs.size()).isEqualTo(5);
		cancel(ada, Json.strings(runs, "id"));
	}

	@Test
	void startRuns_seedGroupPastTheHourlyLimit_is429(@On(Profile.HOSTED) ApiServer hosted) {
		ApiClient ada = hosted.signedIn("ada");
		hosted.db().update("UPDATE workspaces SET max_runs_per_hour = 4 WHERE id = ?", Workspaces.first(ada));
		String project = Json.id(Runs.createProject(ada, Examples.starter(4, 1)));
		ApiResponse response = ada.post("/projects/" + project + "/runs", Json.object("seeds", 5));
		assertThat(response.status()).isEqualTo(429); // 5 > 4: nothing is queued
		assertThat(ada.get("/projects/" + project + "/runs").json()).isEqualTo(Json.array());
	}

	/**
	 * The owner's team workspace holds the project; eve is an outsider (with only her own
	 * personal workspace), vic a viewer of the team.
	 */
	@Test
	void runGroup_outsideItsWorkspace_isNotFound(@On(Profile.HOSTED) ApiServer hosted) {
		ApiClient owner = hosted.signedIn("owner");
		String team = Json.id(Workspaces.create(owner, "Team"));
		String project = Json.id(createProject(owner, team));
		String runs = "/projects/" + project + "/runs";
		String group = owner.post(runs, Json.object("seeds", 2)).json().path("groupId").asString();
		ApiClient eve = hosted.signedIn("eve");
		ApiClient vic = hosted.signedIn("vic");
		Workspaces.join(owner, team, vic, "viewer");

		ApiResponse response = eve.get("/run-groups/" + group);
		assertThat(response.status()).isEqualTo(404);
		assertThat(response.json()).isEqualTo(Json.object("detail",
				Json.object("message", "Run group '" + group + "' not found", "issues", Json.array())));
		assertThat(eve.post(runs, Json.object("seeds", 2)).status()).isEqualTo(404);
		// viewers read groups but start none
		assertThat(vic.get("/run-groups/" + group).status()).isEqualTo(200);
		assertThat(vic.post(runs, Json.object("seeds", 2)).status()).isEqualTo(403);
		waitForGroup(owner, group);
	}

	// -- quick checks

	@Test
	void quickCheck_ofDesignThatDoesNotQualify_givesTheReason(ApiClient client) {
		String project = Json.id(Runs.createProject(client, Examples.starter(8, 2)));
		ApiResponse response = client.post("/projects/" + project + "/runs", Json.object("mode", "sampled"));
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("too short");
		JsonNode analysis = client.post("/analyze", Json.object("document", Examples.starter(8, 2))).json();
		assertThat(analysis.path("quickCheck").path("eligible").asBoolean(true)).isFalse();
		assertThat(response.message()).contains(analysis.path("quickCheck").path("reason").asString());
	}

	// -- helpers

	/** Waits for every run of a group (two minutes each at most); returns the group. */
	private static JsonNode waitForGroup(ApiClient client, String groupId) {
		JsonNode group = client.get("/run-groups/" + groupId).json();
		for (JsonNode run : group.path("runs")) {
			Runs.waitFor(client, Json.id(run), Duration.ofMinutes(2));
		}
		return client.get("/run-groups/" + groupId).expectStatus(200).json();
	}

	/** A project of a short starter design in {@code workspace}. */
	private static JsonNode createProject(ApiClient client, String workspace) {
		return Runs.createProject(client, workspace, Examples.starter(4, 1));
	}

	/** Cancels runs a test no longer needs, sparing the shared worker. */
	private static void cancel(ApiClient client, List<String> runIds) {
		runIds.forEach((id) -> client.post("/runs/" + id + "/cancel"));
	}

}
