package io.archsim.apitests;

import static io.archsim.apitests.Json.object;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * Limits: 429 with Retry-After, workspace profiles, project quotas, calling Prometheus
 * only where allowed, and the API's security headers. From
 * {@code services/api/tests/test_api_limits.py}.
 *
 * <p>
 * The hosted server has a hosted server's limits; each person signed in is new, with a
 * fresh personal workspace, so the counters a test fills are its own.
 *
 * <p>
 * Not ported, because they call {@code archsim_api.limits} directly rather than over
 * HTTP: {@code test_a_counter_allows_up_to_its_limit_then_says_how_long_to_wait}, {@code
 * test_a_refused_batch_costs_nothing},
 * {@code test_counters_hold_across_instances_and_threads} and
 * {@code test_old_windows_are_pruned}. {@code test_profiles_per_kind} and {@code
 * test_an_allowlisted_name_must_resolve_to_a_public_address} are ported in part (see
 * each).
 */
@ApiTest(Profile.HOSTED)
class LimitsTest {

	/** One histogram query, so calibrating has to call the server. */
	private static final String MAPPING = "nodes: {api: {serviceTimeMs: {histogram: 'sum by (le) (rate(x_bucket[1m]))', unit: s}}}";

	// -- 429 on enqueue
	// --------------------------------------------------------------------------

	@Test
	void startingRunsPastTheWorkspaceHourlyLimitIs429(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		setWorkspace(server.db(), Workspaces.first(ada), "max_runs_per_hour", 2);
		String project = Runs.createProject(ada).path("id").asString();
		String url = "/projects/" + project + "/runs";
		assertThat(ada.post(url, object()).status()).isEqualTo(202);
		ApiResponse batch = ada.post(url + "/batch", object("scenarioIds", Json.array(null, null)));
		assertThat(batch.status()).isEqualTo(429); // 1 + 2 > 2: none of the batch is
													// queued
		assertThat(ada.post(url, object()).status()).isEqualTo(202);
		ApiResponse res = ada.post(url, object());
		assertThat(res.status()).isEqualTo(429);
		assertThat(Integer.parseInt(Objects.requireNonNull(res.header("retry-after")))).isBetween(1, 3600);
		assertThat(res.message()).contains("runs for this hour");
		assertThat(ada.get(url).json().size()).isEqualTo(2);
	}

	/** In a workspace of its own (the Python test clears default's limit first). */
	@Test
	void withoutSignInThereIsNoHourlyLimit(@On(Profile.LOCAL) ApiClient client) {
		String workspace = Workspaces.enter(client);
		assertThat(client.get("/workspaces/" + workspace).json().path("maxRunsPerHour").isNull()).isTrue();
		String project = Runs.createProject(client).path("id").asString();
		List<String> runs = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ApiResponse res = client.post("/projects/" + project + "/runs", object());
			assertThat(res.status()).as(res.text()).isEqualTo(202);
			runs.add(res.json().path("id").asString());
		}
		runs.forEach(run -> client.post("/runs/" + run + "/cancel")); // spare the shared
																		// worker
	}

	/** 30 sign-in starts per address and 10 minutes ({@code limits.LOGINS_PER_IP}). */
	@Test
	void signInAttemptsAreLimitedPerAddress(ApiServer server) {
		ApiClient visitor = server.client();
		List<Integer> codes = new ArrayList<>();
		for (int i = 0; i < 31; i++) {
			codes.add(visitor.get("/auth/github/login").status());
		}
		assertThat(codes.subList(0, 30)).containsOnly(302);
		assertThat(codes.get(30)).isEqualTo(429);
		// another visitor: each client sends an address of its own as
		// X-ArchSim-Client-IP, which
		// the server believes from 127.0.0.1, as from the web server in front of it
		assertThat(server.client().get("/auth/github/login").status()).isEqualTo(302);
	}

	// -- profiles: the largest run, runs at once, projects
	// ---------------------------------------

	@Test
	void eachRunCarriesItsWorkspaceLimits(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		String sandbox = Workspaces.insert(server.db(), "sandbox", Workspaces.userId(ada));
		String project = createProject(ada, sandbox, Examples.starter(4, 1)).path("id").asString();
		String run = startRun(ada, project);
		assertThat(runLimits(server.db(), run)).isEqualTo(Map.of("max_requests", 200_000L, "max_wall_seconds", 20.0));
		// 400 req/s for ten minutes: more than a sandbox run may send
		ApiResponse res = ada.post("/projects/" + project + "/runs", object("document", Examples.starter(600, 1)));
		assertThat(res.status()).as(res.text()).isEqualTo(422);
	}

	/**
	 * Ported in part: {@code limits.kind_caps}, {@code calibrate_wall_seconds} and {@code
	 * simulation_limits} are called directly in Python. Over HTTP, the runs at once of
	 * each kind show in the workspaces' {@code maxConcurrentRuns} (hosted: personal 2,
	 * team 4, sandbox 1; local: none, but a sandbox keeps its 1), and a local sandbox's
	 * run carries the sandbox's 20 s. The calibration replay's wall clock (60 s locally,
	 * 20 s hosted) shows nowhere.
	 */
	@Test
	void profilesPerKind(ApiServer server, @On(Profile.LOCAL) ApiServer local) {
		ApiClient ada = server.signedIn("ada");
		String personal = Workspaces.first(ada);
		String team = Workspaces.create(ada, "Team").path("id").asString();
		String sandbox = Workspaces.insert(server.db(), "sandbox", Workspaces.userId(ada));
		JsonNode mine = ada.get("/workspaces").json();
		assertThat(atOnce(mine, personal)).isEqualTo(2);
		assertThat(atOnce(mine, team)).isEqualTo(4);
		assertThat(atOnce(mine, sandbox)).isEqualTo(1);

		ApiClient client = local.client();
		String localTeam = Workspaces.create(client, "Team").path("id").asString();
		String localSandbox = Workspaces.insert(local.db(), "sandbox", "local");
		JsonNode workspaces = client.get("/workspaces").json();
		assertThat(atOnce(workspaces, localTeam)).isNull();
		assertThat(atOnce(workspaces, localSandbox)).isEqualTo(1);
		String project = createProject(client, localSandbox, Examples.starter(4, 1)).path("id").asString();
		String run = startRun(client, project);
		assertThat(runLimits(local.db(), run).get("max_wall_seconds")).isEqualTo(20.0);
	}

	/** The local server is shared, so its {@code default} is found among the others. */
	@Test
	void workspacesReportTheLimitsThatApply(ApiServer server, @On(Profile.LOCAL) ApiClient local) {
		ApiClient ada = server.signedIn("ada");
		JsonNode listed = ada.get("/workspaces").json();
		assertThat(listed.size()).isEqualTo(1);
		JsonNode mine = listed.get(0);
		assertThat(List.<Object>of(mine.path("kind").asString(), mine.path("maxConcurrentRuns").asInt(),
				mine.path("maxRunsPerHour").asInt()))
			.containsExactly("personal", 2, 60);
		String id = mine.path("id").asString();
		setWorkspace(server.db(), id, "max_concurrent_runs", 5);
		assertThat(ada.get("/workspaces/" + id).json().path("maxConcurrentRuns").asInt()).isEqualTo(5);
		JsonNode def = Json.find(local.get("/workspaces").json(), "id", "default");
		assertThat(def.path("maxConcurrentRuns").isNull()).isTrue();
		assertThat(def.path("maxRunsPerHour").isNull()).isTrue();
	}

	@Test
	void aSandboxHoldsFiveProjects(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		String sandbox = Workspaces.insert(server.db(), "sandbox", Workspaces.userId(ada));
		String url = ApiClient.query("/projects", "workspace", sandbox);
		for (int i = 0; i < 5; i++) {
			assertThat(ada.post(url, object("document", Examples.starter())).status()).isEqualTo(201);
		}
		ApiResponse res = ada.post(url, object("document", Examples.starter()));
		assertThat(res.status()).isEqualTo(409);
		assertThat(res.message()).contains("at most 5 projects");
	}

	// -- calling Prometheus
	// ----------------------------------------------------------------------

	@Test
	void hostedServersCallNoPrometheusByDefault(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		for (String url : List.of("http://127.0.0.1:9090", "http://10.0.0.5:9090", "http://prom.example.com")) {
			ApiResponse res = calibrate(ada, url, null);
			assertThat(res.status()).as(res.text()).isEqualTo(422);
			assertThat(res.message()).contains("ARCHSIM_OUTBOUND");
		}
	}

	/**
	 * Ported in part: the Python test makes {@code prom.corp.test} resolve to each of six
	 * private addresses by replacing the resolver in-process. Over HTTP only a name that
	 * really resolves to a private address can be checked: {@code localhost}, listed on
	 * the allowlist profile's server (127.0.0.1, and ::1 where it resolves to that too).
	 */
	@Test
	void anAllowlistedNameMustResolveToAPublicAddress(@On(Profile.ALLOWLIST) ApiClient client) {
		ApiResponse res = calibrate(client, "http://localhost:9090", null);
		assertThat(res.status()).as(res.text()).isEqualTo(422);
		assertThat(res.message()).contains("non-public");
		ApiResponse other = calibrate(client, "http://127.0.0.1:9090", null); // not on
																				// the
																				// list at
																				// all
		assertThat(other.message()).contains("not allowed");
	}

	@Test
	void sandboxesNeverCallOutEvenOnAnOpenServer(@On(Profile.LOCAL) ApiServer local) {
		ApiClient client = local.client();
		String sandbox = Workspaces.insert(local.db(), "sandbox", "local");
		ApiResponse res = calibrate(client, "http://127.0.0.1:9", sandbox);
		assertThat(res.status()).as(res.text()).isEqualTo(422);
		assertThat(res.message()).contains("ARCHSIM_OUTBOUND");
		// the default workspace on a local server still may (and fails to connect here)
		res = calibrate(client, "http://127.0.0.1:9", null);
		assertThat(res.message()).doesNotContain("ARCHSIM_OUTBOUND");
	}

	/**
	 * Viv is a viewer of a team workspace of Ada's (the Python test makes her one of
	 * Ada's own).
	 */
	@Test
	void calibratingNeedsAnEditor(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		String team = Workspaces.create(ada, "Team").path("id").asString();
		ApiClient viv = server.signedIn("viv");
		Workspaces.join(ada, team, viv, "viewer");
		ApiResponse res = viv.post(ApiClient.query("/calibrate", "workspace", team),
				object("document", Examples.starter(), "source", "otlp"));
		assertThat(res.status()).as(res.text()).isEqualTo(403);
	}

	// -- security headers
	// ------------------------------------------------------------------------

	@Test
	void apiAnswersCarrySecurityHeaders(ApiServer server) {
		ApiResponse res = server.signedIn("ada").get("/auth/me");
		assertThat(res.header("x-content-type-options")).isEqualTo("nosniff");
		assertThat(res.header("x-frame-options")).isEqualTo("DENY");
		assertThat(res.header("referrer-policy")).isEqualTo("no-referrer");
		assertThat(res.header("content-security-policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
		assertThat(res.header("cache-control")).isEqualTo("no-store");
		assertThat(res.header("strict-transport-security")).startsWith("max-age=");
		ApiResponse docs = server.client().get("/docs");
		assertThat(docs.header("content-security-policy")).isNull(); // its script comes
																		// from a CDN
	}

	@Test
	void aLocalServerSendsNoHsts(@On(Profile.LOCAL) ApiClient client) {
		ApiResponse res = client.get("/health");
		assertThat(res.header("x-content-type-options")).isEqualTo("nosniff");
		assertThat(res.header("strict-transport-security")).isNull();
	}

	// -- helpers
	// -----------------------------------------------------------------------------------

	/** Sets a column of one workspace (the test's own) in the database. */
	private static void setWorkspace(Db db, String workspace, String column, @Nullable Object value) {
		if (!List.of("max_runs_per_hour", "max_concurrent_runs").contains(column)) {
			throw new IllegalArgumentException(column);
		}
		db.update("UPDATE workspaces SET " + column + " = ? WHERE id = ?", value, workspace);
	}

	private static JsonNode createProject(ApiClient client, String workspace, JsonNode document) {
		ApiResponse res = client.post(ApiClient.query("/projects", "workspace", workspace),
				object("document", document));
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		return res.json();
	}

	private static String startRun(ApiClient client, String project) {
		ApiResponse res = client.post("/projects/" + project + "/runs", object());
		assertThat(res.status()).as(res.text()).isEqualTo(202);
		return res.json().path("id").asString();
	}

	/** A run's stored limits, {@code max_requests} and {@code max_wall_seconds}. */
	private static Map<String, @Nullable Object> runLimits(Db db, String run) {
		List<Map<String, @Nullable Object>> rows = db
			.rows("SELECT max_requests, max_wall_seconds FROM simulation_runs WHERE id = ?", run);
		assertThat(rows).hasSize(1);
		return rows.get(0);
	}

	/** {@code maxConcurrentRuns} of one of the listed workspaces (null: no limit). */
	private static @Nullable Integer atOnce(JsonNode workspaces, String id) {
		JsonNode value = Json.find(workspaces, "id", id).path("maxConcurrentRuns");
		return value.isNull() ? null : value.asInt();
	}

	/**
	 * {@code POST /calibrate} from Prometheus at {@code url}, in {@code workspace} (or
	 * the default).
	 */
	private static ApiResponse calibrate(ApiClient client, String url, @Nullable String workspace) {
		return client.post(ApiClient.query("/calibrate", "workspace", workspace),
				object("document", Examples.starter(), "source", "prometheus", "mapping", MAPPING, "prometheusUrl", url,
						"range", "2026-10-01T00:00:00Z/2026-10-01T01:00:00Z"));
	}

}
