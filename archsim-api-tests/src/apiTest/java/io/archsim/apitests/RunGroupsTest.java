package io.archsim.apitests;

import static io.archsim.apitests.Json.items;
import static io.archsim.apitests.Json.object;
import static io.archsim.apitests.Json.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Seed groups and quick checks: {@code seeds} and {@code mode} on {@code POST
 * /projects/{id}/runs}, {@code GET /run-groups/{id}}, the seeds each workspace may run, workspace
 * scoping, and reruns that reproduce a group's run. From {@code
 * services/api/tests/test_api_run_groups.py}.
 *
 * <p>Not ported: {@code test_a_rerun_of_a_quick_check_is_a_quick_check} and {@code
 * test_quick_checks_with_seeds} shrink the engine's sampling thresholds in-process (the {@code
 * tiny_samples} fixture), which no server can be asked to do; without it, a design long enough
 * to qualify for a quick check takes minutes to run. {@code test_seed_limits_per_workspace_kind}
 * is ported in part (see it).
 */
@ApiTest(Profile.LOCAL)
class RunGroupsTest {

    /** En dash, as in "seeds 42–44" ({@code archsim_engine.seeds.DASH}). */
    private static final String DASH = "–";

    @Test
    void aSeedGroupRunsOneInputWithConsecutiveSeeds(ApiClient client) {
        ObjectNode doc = Examples.starter(4, 1);
        doc.set("objectives", object("latencyP95Ms", 10_000, "availability", 0.5));
        String project = Runs.createProject(client, doc).path("id").asString();
        ApiResponse res = client.post("/projects/" + project + "/runs", object("seeds", 3));
        assertThat(res.status()).as(res.text()).isEqualTo(202);
        JsonNode first = res.json();
        assertThat(first.path("groupId").asString("")).isNotEmpty();
        assertThat(first.path("seedIndex").asInt(-1)).isZero();
        assertThat(first.path("seed").asLong()).isEqualTo(42);
        assertThat(first.path("mode").asString()).isEqualTo("full");
        JsonNode group = waitForGroup(client, first.path("groupId").asString());
        assertThat(List.<Object>of(
                        group.path("kind").asString(),
                        group.path("baseSeed").asLong(),
                        group.path("size").asInt(),
                        group.path("status").asString()))
                .containsExactly("seeds", 42L, 3, "succeeded");
        List<JsonNode> runs = items(group.path("runs"));
        assertThat(runs).extracting(r -> r.path("seed").asLong()).containsExactly(42L, 43L, 44L);
        assertThat(runs).extracting(r -> r.path("seedIndex").asInt()).containsExactly(0, 1, 2);
        assertThat(runs.get(1).path("label").asString()).isEqualTo("Current design · seed 2 of 3");
        JsonNode summary = group.path("summary");
        assertThat(List.<Object>of(
                        summary.path("finished").asInt(),
                        summary.path("met").asInt(),
                        summary.path("verdict").asString()))
                .containsExactly(3, 3, "holds");
        assertThat(summary.path("text").asString())
                .startsWith("Meets the objectives in 3 of 3 runs · p95 ")
                .endsWith("· seeds 42" + DASH + "44");
        double minP95 = runs.stream()
                .mapToDouble(r -> r.path("summary").path("p95").asDouble())
                .min()
                .orElseThrow();
        assertThat(summary.path("latencyMs").path("p95").path("min").asDouble()).isEqualTo(minP95);
        // one stored document; each report ran (and records) its own seed
        List<JsonNode> reports = runs.stream()
                .map(r -> client.get("/runs/" + r.path("id").asString() + "/report")
                        .json())
                .toList();
        assertThat(reports)
                .extracting(r -> r.path("provenance").path("seed").asLong())
                .containsExactly(42L, 43L, 44L);
        assertThat(reports)
                .extracting(r -> r.path("provenance").path("inputDigest").asString())
                .doesNotHaveDuplicates()
                .hasSize(3);
        List<JsonNode> inputs = runs.stream()
                .map(r -> client.get("/runs/" + r.path("id").asString() + "/input")
                        .json())
                .toList();
        assertThat(inputs).allMatch(i -> i.equals(inputs.get(0)));
        assertThat(inputs.get(0).path("workload").path("seed").asLong()).isEqualTo(42);
        // a single run of the same input is the group's first run
        JsonNode single = Runs.waitFor(client, startRun(client, project, object()));
        assertThat(single.path("resultDigest")).isEqualTo(runs.get(0).path("resultDigest"));
        assertThat(single.path("groupId").isNull()).isTrue();
        assertThat(single.path("seedIndex").isNull()).isTrue();

        // rerunning a group's run reproduces it, seed included
        String third = runs.get(2).path("id").asString();
        JsonNode again = client.post("/runs/" + third + "/rerun").json();
        assertThat(again.path("seed").asLong()).isEqualTo(44);
        assertThat(again.path("reproducesRunId").asString()).isEqualTo(third);
        assertThat(Runs.waitFor(client, again.path("id").asString()).path("resultDigest"))
                .isEqualTo(runs.get(2).path("resultDigest"));
    }

    @Test
    void theGroupReadsItsObjectivesFromTheInput(ApiClient client) {
        ObjectNode doc = Examples.starter(4, 1);
        doc.set("objectives", object("latencyP95Ms", 0.001)); // nothing is that fast
        String project = Runs.createProject(client, doc).path("id").asString();
        JsonNode first = client.post("/projects/" + project + "/runs", object("seeds", 2))
                .json();
        JsonNode group = waitForGroup(client, first.path("groupId").asString());
        assertThat(group.path("summary").path("verdict").asString()).isEqualTo("fails");
        assertThat(group.path("summary").path("text").asString()).startsWith("Misses the objectives in 2 of 2 runs");
    }

    /** The ten runs are cancelled at the end: the Python test's server has no worker. */
    @Test
    void seedsTheBaseSeedAndTheSchemaAreChecked(ApiClient client) {
        String project =
                Runs.createProject(client, Examples.starter(4, 1)).path("id").asString();
        String url = "/projects/" + project + "/runs";
        assertThat(client.post(url, object("seeds", 0)).status()).isEqualTo(422);
        assertThat(client.post(url, object("seeds", 11)).status()).isEqualTo(422);
        ApiResponse ten = client.post(url, object("seeds", 10)); // locally, up to 10
        assertThat(ten.status()).as(ten.text()).isEqualTo(202);
        JsonNode group = client.get("/run-groups/" + ten.json().path("groupId").asString())
                .json();
        assertThat(group.path("runs").size()).isEqualTo(10);
        ObjectNode late = Examples.starter(4, 1);
        ((ObjectNode) late.path("workload")).put("seed", (1L << 32) - 2);
        ApiResponse res = client.post(url, object("seeds", 3, "document", late));
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.message()).contains("go past the largest");
        assertThat(client.get("/run-groups/nope").status()).isEqualTo(404);
        cancel(client, strings(group.path("runs"), "id"));
    }

    /**
     * Ported in part: the Python test calls {@code limits.max_seeds} for each kind. Over HTTP, six
     * seeds are refused in hosted personal, team and sandbox workspaces and in a local sandbox
     * (five at most); a local team workspace's ten are in {@link
     * #seedsTheBaseSeedAndTheSchemaAreChecked} ({@code default} is a team workspace).
     */
    @Test
    void seedLimitsPerWorkspaceKind(@On(Profile.HOSTED) ApiServer hosted, ApiServer local) {
        ApiClient ada = hosted.signedIn("ada");
        List<String> workspaces = List.of(
                Workspaces.first(ada),
                Workspaces.create(ada, "Team").path("id").asString(),
                Workspaces.insert(hosted.db(), "sandbox", Workspaces.userId(ada)));
        for (String workspace : workspaces) {
            assertAtMostFiveSeeds(ada, workspace);
        }
        assertAtMostFiveSeeds(local.client(), Workspaces.insert(local.db(), "sandbox", "local"));
    }

    private static void assertAtMostFiveSeeds(ApiClient client, String workspace) {
        String project = createProject(client, workspace).path("id").asString();
        ApiResponse res = client.post("/projects/" + project + "/runs", object("seeds", 6));
        assertThat(res.status()).as(workspace + ": " + res.text()).isEqualTo(422);
        assertThat(res.message()).contains("at most 5 seeds");
    }

    @Test
    void aHostedWorkspaceRunsAtMostFiveSeeds(@On(Profile.HOSTED) ApiServer hosted) {
        ApiClient ada = hosted.signedIn("ada");
        String project =
                Runs.createProject(ada, Examples.starter(4, 1)).path("id").asString();
        String url = "/projects/" + project + "/runs";
        ApiResponse res = ada.post(url, object("seeds", 6));
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.message()).contains("at most 5 seeds");
        assertThat(ada.post(url, object("seeds", 5)).status()).isEqualTo(202);
        JsonNode runs = ada.get(url).json();
        assertThat(runs.size()).isEqualTo(5);
        cancel(ada, strings(runs, "id"));
    }

    @Test
    void aSeedGroupCountsEveryRunAgainstTheHourlyLimit(@On(Profile.HOSTED) ApiServer hosted) {
        ApiClient ada = hosted.signedIn("ada");
        hosted.db().update("UPDATE workspaces SET max_runs_per_hour = 4 WHERE id = ?", Workspaces.first(ada));
        String project =
                Runs.createProject(ada, Examples.starter(4, 1)).path("id").asString();
        ApiResponse res = ada.post("/projects/" + project + "/runs", object("seeds", 5));
        assertThat(res.status()).isEqualTo(429); // 5 > 4: nothing is queued
        assertThat(ada.get("/projects/" + project + "/runs").json()).isEqualTo(Json.array());
    }

    /**
     * The owner's team workspace holds the project; eve is an outsider (with only her own
     * personal workspace), vic a viewer of the team.
     */
    @Test
    void groupsBelongToTheirWorkspace(@On(Profile.HOSTED) ApiServer hosted) {
        ApiClient owner = hosted.signedIn("owner");
        String team = Workspaces.create(owner, "Team").path("id").asString();
        String project = createProject(owner, team).path("id").asString();
        String runs = "/projects/" + project + "/runs";
        String group =
                owner.post(runs, object("seeds", 2)).json().path("groupId").asString();
        ApiClient eve = hosted.signedIn("eve");
        ApiClient vic = hosted.signedIn("vic");
        Workspaces.join(owner, team, vic, "viewer");

        ApiResponse res = eve.get("/run-groups/" + group);
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.json())
                .isEqualTo(object(
                        "detail", object("message", "Run group '" + group + "' not found", "issues", Json.array())));
        assertThat(eve.post(runs, object("seeds", 2)).status()).isEqualTo(404);
        // viewers read groups but start none
        assertThat(vic.get("/run-groups/" + group).status()).isEqualTo(200);
        assertThat(vic.post(runs, object("seeds", 2)).status()).isEqualTo(403);
        waitForGroup(owner, group);
    }

    // -- quick checks ----------------------------------------------------------------------------

    @Test
    void aDesignThatDoesNotQualifyGetsTheReason(ApiClient client) {
        String project =
                Runs.createProject(client, Examples.starter(8, 2)).path("id").asString();
        ApiResponse res = client.post("/projects/" + project + "/runs", object("mode", "sampled"));
        assertThat(res.status()).isEqualTo(422);
        assertThat(res.message()).contains("too short");
        JsonNode analysis = client.post("/analyze", object("document", Examples.starter(8, 2)))
                .json();
        assertThat(analysis.path("quickCheck").path("eligible").asBoolean(true)).isFalse();
        assertThat(res.message())
                .contains(analysis.path("quickCheck").path("reason").asString());
    }

    // -- helpers -----------------------------------------------------------------------------------

    /** Waits for every run of a group (two minutes each at most); returns the group. */
    private static JsonNode waitForGroup(ApiClient client, String groupId) {
        JsonNode group = client.get("/run-groups/" + groupId).json();
        for (JsonNode run : group.path("runs")) {
            Runs.waitFor(client, run.path("id").asString(), Duration.ofMinutes(2));
        }
        ApiResponse res = client.get("/run-groups/" + groupId);
        assertThat(res.status()).as(res.text()).isEqualTo(200);
        return res.json();
    }

    private static JsonNode createProject(ApiClient client, String workspace) {
        ApiResponse res = client.post(
                ApiClient.query("/projects", "workspace", workspace), object("document", Examples.starter(4, 1)));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        return res.json();
    }

    private static String startRun(ApiClient client, String project, JsonNode body) {
        ApiResponse res = client.post("/projects/" + project + "/runs", body);
        assertThat(res.status()).as(res.text()).isEqualTo(202);
        return res.json().path("id").asString();
    }

    /** Cancels runs a test no longer needs, sparing the shared worker. */
    private static void cancel(ApiClient client, List<String> runIds) {
        runIds.forEach(id -> client.post("/runs/" + id + "/cancel"));
    }
}
