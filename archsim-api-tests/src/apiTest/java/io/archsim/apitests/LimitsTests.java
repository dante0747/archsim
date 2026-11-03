package io.archsim.apitests;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

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
class LimitsTests {

	/** One histogram query, so calibrating has to call the server. */
	private static final String MAPPING = "nodes: {api: {serviceTimeMs: "
			+ "{histogram: 'sum by (le) (rate(x_bucket[1m]))', unit: s}}}";

	// -- 429 on enqueue

	@Test
	void startRuns_pastWorkspaceHourlyLimit_is429(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		setWorkspace(server.db(), Workspaces.first(ada), "max_runs_per_hour", 2);
		String project = Json.id(Runs.createProject(ada));
		String url = "/projects/" + project + "/runs";
		assertThat(ada.post(url, Json.object()).status()).isEqualTo(202);
		ApiResponse batch = ada.post(url + "/batch", Json.object("scenarioIds", Json.array(null, null)));
		// 1 + 2 > 2: none of the batch is queued
		assertThat(batch.status()).isEqualTo(429);
		assertThat(ada.post(url, Json.object()).status()).isEqualTo(202);
		ApiResponse response = ada.post(url, Json.object());
		assertThat(response.status()).isEqualTo(429);
		assertThat(response.header("retry-after").map(Integer::parseInt))
			.hasValueSatisfying((seconds) -> assertThat(seconds).isBetween(1, 3600));
		assertThat(response.message()).contains("runs for this hour");
		assertThat(ada.get(url).json().size()).isEqualTo(2);
	}

	/** In a workspace of its own (the Python test clears default's limit first). */
	@Test
	void startRuns_withoutSignIn_haveNoHourlyLimit(@On(Profile.LOCAL) ApiClient client) {
		String workspace = Workspaces.enter(client);
		assertThat(client.get("/workspaces/" + workspace).json().path("maxRunsPerHour").isNull()).isTrue();
		String project = Json.id(Runs.createProject(client));
		List<String> runs = new ArrayList<>();
		for (int i = 0; i < 5; i++) {
			ApiResponse response = client.post("/projects/" + project + "/runs", Json.object());
			assertThat(response.status()).as(response.text()).isEqualTo(202);
			runs.add(Json.id(response.json()));
		}
		// spare the shared worker
		runs.forEach((run) -> client.post("/runs/" + run + "/cancel"));
	}

	/** 30 sign-in starts per address and 10 minutes ({@code limits.LOGINS_PER_IP}). */
	@Test
	void login_pastAttemptsPerAddress_is429(ApiServer server) {
		ApiClient visitor = server.client();
		List<Integer> codes = new ArrayList<>();
		for (int i = 0; i < 31; i++) {
			codes.add(visitor.get("/auth/github/login").status());
		}
		assertThat(codes.subList(0, 30)).containsOnly(302);
		assertThat(codes.get(30)).isEqualTo(429);
		// another visitor: each client sends an address of its own as
		// X-ArchSim-Client-IP, which the server believes from 127.0.0.1, as from the web
		// server in front of it
		assertThat(server.client().get("/auth/github/login").status()).isEqualTo(302);
	}

	// -- profiles: the largest run, runs at once, projects

	@Test
	void startRun_inSandbox_carriesItsWorkspaceLimits(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		String sandbox = Workspaces.insert(server.db(), "sandbox", Workspaces.userId(ada));
		String project = Json.id(Runs.createProject(ada, sandbox, Examples.starter(4, 1)));
		String run = Runs.startRun(ada, project);
		assertThat(runLimits(server.db(), run)).isEqualTo(Map.of("max_requests", 200_000L, "max_wall_seconds", 20.0));
		// 400 req/s for ten minutes: more than a sandbox run may send
		ApiResponse response = ada.post("/projects/" + project + "/runs",
				Json.object("document", Examples.starter(600, 1)));
		assertThat(response.status()).as(response.text()).isEqualTo(422);
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
	void workspaces_ofEachKind_getTheirProfile(ApiServer server, @On(Profile.LOCAL) ApiServer local) {
		ApiClient ada = server.signedIn("ada");
		String personal = Workspaces.first(ada);
		String team = Json.id(Workspaces.create(ada, "Team"));
		String sandbox = Workspaces.insert(server.db(), "sandbox", Workspaces.userId(ada));
		JsonNode mine = ada.get("/workspaces").json();
		assertThat(atOnce(mine, personal)).isEqualTo(2);
		assertThat(atOnce(mine, team)).isEqualTo(4);
		assertThat(atOnce(mine, sandbox)).isEqualTo(1);

		ApiClient client = local.client();
		String localTeam = Json.id(Workspaces.create(client, "Team"));
		String localSandbox = Workspaces.insert(local.db(), "sandbox", "local");
		JsonNode workspaces = client.get("/workspaces").json();
		assertThat(atOnce(workspaces, localTeam)).isNull();
		assertThat(atOnce(workspaces, localSandbox)).isEqualTo(1);
		String project = Json.id(Runs.createProject(client, localSandbox, Examples.starter(4, 1)));
		String run = Runs.startRun(client, project);
		assertThat(runLimits(local.db(), run).get("max_wall_seconds")).isEqualTo(20.0);
	}

	/** The local server is shared, so its {@code default} is found among the others. */
	@Test
	void workspaces_whenRead_reportTheLimitsThatApply(ApiServer server, @On(Profile.LOCAL) ApiClient local) {
		ApiClient ada = server.signedIn("ada");
		JsonNode listed = ada.get("/workspaces").json();
		assertThat(listed.size()).isEqualTo(1);
		JsonNode mine = listed.get(0);
		assertThat(List.<Object>of(mine.path("kind").asString(), mine.path("maxConcurrentRuns").asInt(),
				mine.path("maxRunsPerHour").asInt()))
			.containsExactly("personal", 2, 60);
		String id = Json.id(mine);
		setWorkspace(server.db(), id, "max_concurrent_runs", 5);
		assertThat(ada.get("/workspaces/" + id).json().path("maxConcurrentRuns").asInt()).isEqualTo(5);
		JsonNode localDefault = Json.find(local.get("/workspaces").json(), "id", "default");
		assertThat(localDefault.path("maxConcurrentRuns").isNull()).isTrue();
		assertThat(localDefault.path("maxRunsPerHour").isNull()).isTrue();
	}

	@Test
	void createProject_inSandboxWithFiveProjects_isConflict(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		String sandbox = Workspaces.insert(server.db(), "sandbox", Workspaces.userId(ada));
		String url = ApiClient.query("/projects", "workspace", sandbox);
		for (int i = 0; i < 5; i++) {
			assertThat(ada.post(url, Json.object("document", Examples.starter())).status()).isEqualTo(201);
		}
		ApiResponse response = ada.post(url, Json.object("document", Examples.starter()));
		assertThat(response.status()).isEqualTo(409);
		assertThat(response.message()).contains("at most 5 projects");
	}

	// -- calling Prometheus

	@Test
	void calibrate_onHostedServer_callsNoPrometheusByDefault(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		for (String url : List.of("http://127.0.0.1:9090", "http://10.0.0.5:9090", "http://prom.example.com")) {
			ApiResponse response = calibrate(ada, url, null);
			assertThat(response.status()).as(response.text()).isEqualTo(422);
			assertThat(response.message()).contains("ARCHSIM_OUTBOUND");
		}
	}

	/**
	 * Ported in part: the Python test makes {@code prom.corp.test} resolve to each of six
	 * private addresses by replacing the resolver in-process. Over HTTP only a name that
	 * really resolves to a private address can be checked: {@code localhost}, listed on
	 * the allowlist profile's server (127.0.0.1, and ::1 where it resolves to that too).
	 */
	@Test
	void calibrate_withAllowlistedPrivateName_isRefused(@On(Profile.ALLOWLIST) ApiClient client) {
		ApiResponse response = calibrate(client, "http://localhost:9090", null);
		assertThat(response.status()).as(response.text()).isEqualTo(422);
		assertThat(response.message()).contains("non-public");
		// not on the list at all
		ApiResponse other = calibrate(client, "http://127.0.0.1:9090", null);
		assertThat(other.message()).contains("not allowed");
	}

	@Test
	void calibrate_inSandboxOnOpenServer_neverCallsOut(@On(Profile.LOCAL) ApiServer local) {
		ApiClient client = local.client();
		String sandbox = Workspaces.insert(local.db(), "sandbox", "local");
		ApiResponse response = calibrate(client, "http://127.0.0.1:9", sandbox);
		assertThat(response.status()).as(response.text()).isEqualTo(422);
		assertThat(response.message()).contains("ARCHSIM_OUTBOUND");
		// the default workspace on a local server still may (and fails to connect here)
		response = calibrate(client, "http://127.0.0.1:9", null);
		assertThat(response.message()).doesNotContain("ARCHSIM_OUTBOUND");
	}

	/**
	 * Viv is a viewer of a team workspace of Ada's (the Python test makes her one of
	 * Ada's own).
	 */
	@Test
	void calibrate_asViewer_isForbidden(ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		String team = Json.id(Workspaces.create(ada, "Team"));
		ApiClient viv = server.signedIn("viv");
		Workspaces.join(ada, team, viv, "viewer");
		ApiResponse response = viv.post(ApiClient.query("/calibrate", "workspace", team),
				Json.object("document", Examples.starter(), "source", "otlp"));
		assertThat(response.status()).as(response.text()).isEqualTo(403);
	}

	// -- security headers

	@Test
	void answers_ofHostedServer_carrySecurityHeaders(ApiServer server) {
		ApiResponse response = server.signedIn("ada").get("/auth/me");
		assertThat(response.header("x-content-type-options")).hasValue("nosniff");
		assertThat(response.header("x-frame-options")).hasValue("DENY");
		assertThat(response.header("referrer-policy")).hasValue("no-referrer");
		assertThat(response.header("content-security-policy")).hasValue("default-src 'none'; frame-ancestors 'none'");
		assertThat(response.header("cache-control")).hasValue("no-store");
		assertThat(response.header("strict-transport-security"))
			.hasValueSatisfying((transportSecurity) -> assertThat(transportSecurity).startsWith("max-age="));
		ApiResponse docs = server.client().get("/docs");
		// its script comes from a CDN
		assertThat(docs.header("content-security-policy")).isEmpty();
	}

	@Test
	void answers_ofLocalServer_sendNoHsts(@On(Profile.LOCAL) ApiClient client) {
		ApiResponse response = client.get("/health");
		assertThat(response.header("x-content-type-options")).hasValue("nosniff");
		assertThat(response.header("strict-transport-security")).isEmpty();
	}

	// -- helpers

	/** Sets a column of one workspace (the test's own) in the database. */
	private static void setWorkspace(Db db, String workspace, String column, @Nullable Object value) {
		if (!List.of("max_runs_per_hour", "max_concurrent_runs").contains(column)) {
			throw new IllegalArgumentException(column);
		}
		db.update("UPDATE workspaces SET " + column + " = ? WHERE id = ?", value, workspace);
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
				Json.object("document", Examples.starter(), "source", "prometheus", "mapping", MAPPING, "prometheusUrl",
						url, "range", "2026-10-01T00:00:00Z/2026-10-01T01:00:00Z"));
	}

}
