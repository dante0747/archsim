package io.archsim.apitests;

import java.time.Duration;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Projects, persistence, import and export, runs, reports and comparison, without
 * sign-in. From {@code services/api/tests/test_api_endpoints.py}.
 */
@ApiTest(Profile.LOCAL)
class EndpointsTests {

	@Test
	void meta_ofLocalServer_describesCatalogExamplesAndSchema(ApiClient client) {
		assertThat(client.get("/health").json()).isEqualTo(Json.object("status", "ok", "database", "ok"));
		JsonNode meta = client.get("/meta").json();
		assertThat(meta.path("engineVersion").asString()).isNotEmpty();
		assertThat(meta.path("schemaVersion").asString()).isEqualTo("1.11");
		assertThat(meta.path("database").asString()).isIn("sqlite", "postgresql");
		JsonNode catalog = client.get("/components").json();
		assertThat(Json.strings(catalog.path("components"), "type")).contains("traffic_source", "load_balancer",
				"service", "cache", "database");
		assertThat(Json.strings(catalog.path("edgeFields"), "key")).contains("latencyMs");
		JsonNode examples = client.get("/examples").json();
		assertThat(Json.strings(examples, "id")).contains("cache-aside-starter", "queueing-lab",
				"retry-storm-ttl-cache");
		assertThat(client.get("/examples/cache-aside-starter").json().path("schemaVersion").asString())
			.isEqualTo("1.3");
		assertThat(client.get("/examples/nope").status()).isEqualTo(404);
		assertThat(client.get("/schema/architecture").json().path("title").asString())
			.isEqualTo("ArchitectureDocument");
		assertThat(client.get("/openapi.json").json().path("paths").has("/api/v1/projects")).isTrue();
	}

	@Test
	void validate_withBrokenDocument_reportsIssues(ApiClient client) {
		JsonNode good = client.post("/validate", Json.object("document", Examples.starter())).json();
		assertThat(good).isEqualTo(Json.object("valid", true, "issues", Json.array()));
		// cache misses that nothing reads: the API no longer reads the DB on a miss
		ObjectNode bad = Examples.starter();
		for (JsonNode edge : bad.path("architecture").path("edges")) {
			if (Json.id(edge).equals("api-db")) {
				((ObjectNode) edge.path("config")).put("readsOnMiss", false);
			}
		}
		JsonNode validation = client.post("/validate", Json.object("document", bad)).json();
		assertThat(validation.path("valid").asBoolean()).isFalse();
		assertThat(validation.path("issues").get(0).path("code").asString()).isEqualTo("cache.miss_unrouted");
		assertThat(validation.path("issues").get(0).path("nodeId").asString()).isEqualTo("cache");
		JsonNode scenario = client.post("/validate", Json.object("document", Examples.starter(), "scenarioId", "nope"))
			.json();
		assertThat(scenario.path("issues").get(0).path("code").asString()).isEqualTo("scenario.unknown");
	}

	@Test
	void analyze_withStarterDocument_returnsSizesCostAndReview(ApiClient client) {
		ApiResponse response = client.post("/analyze", Json.object("document", Examples.starter()));
		assertThat(response.status()).isEqualTo(200);
		JsonNode analysis = response.json();
		JsonNode api = analysis.path("sizing").path("nodes").path("api");
		assertThat(api.path("specs").path("cpuCores").path("source").asString()).isEqualTo("auto");
		assertThat(api.path("auto").path("cpuCores").asDouble()).isGreaterThanOrEqualTo(1);
		assertThat(api.path("pricePerHour").asDouble()).isPositive();
		assertThat(api.path("why").path("cpuCores").asString()).contains("req/s per replica");
		assertThat(analysis.path("cost").path("monthly").asDouble()).isPositive();
		assertThat(analysis.path("cost").path("byNode").isEmpty()).isFalse();
		assertThat(Json.strings(analysis.path("review"), "rule")).isNotEmpty();
		int counted = analysis.path("reviewCounts").valueStream().mapToInt(JsonNode::asInt).sum();
		assertThat(counted).isEqualTo(analysis.path("review").size());
		double peakRps = Examples.starter().path("workload").path("rps").asDouble();
		assertThat(analysis.path("peakRps").asDouble()).isEqualTo(peakRps);

		JsonNode scenario = client
			.post("/analyze", Json.object("document", Examples.starter(), "scenarioId", "three-api-replicas"))
			.json();
		assertThat(scenario.path("scenarioId").asString()).isEqualTo("three-api-replicas");
		// the scenario runs on the design's sizes: same auto size, more replicas billed
		assertThat(scenario.path("sizing").path("nodes").path("api").path("auto")).isEqualTo(api.path("auto"));
		assertThat(scenario.path("cost").path("monthly").asDouble())
			.isGreaterThan(analysis.path("cost").path("monthly").asDouble());
		ApiResponse bad = client.post("/analyze", Json.object("document", Json.object("kind", "nope")));
		assertThat(bad.status()).isEqualTo(422);
		assertThat(bad.json().path("detail").path("issues").isEmpty()).isFalse();
	}

	/** {@link Profile#LOCAL} adds two snapshots of a made-up provider. */
	@Test
	void pricing_withPinnedSnapshot_listsEverySnapshotAndUsesThePinnedOne(ApiClient client) {
		JsonNode listed = client.get("/pricing").json().path("sources");
		assertThat(listed.get(0).path("provider").asString()).isEqualTo("generic");
		// the AWS snapshot the engine ships is listed too
		assertThat(Json.items(listed)).anyMatch((source) -> source.path("provider").asString().equals("aws")
				&& source.path("region").asString().equals("us-east-1"));
		List<JsonNode> sources = Json.items(listed)
			.stream()
			.filter((source) -> source.path("provider").asString().equals("example-cloud"))
			.toList();
		assertThat(sources)
			.extracting((source) -> source.path("date").asString() + " " + source.path("newest").asBoolean())
			.containsExactly("2026-11-01 true", "2026-10-01 false");
		assertThat(Json.items(listed).subList(1, listed.size()))
			.allMatch((source) -> source.path("sha256").asString().length() == 64);
		ObjectNode document = Examples.starter();
		document.set("pricing",
				Json.object("provider", "example-cloud", "region", "test-1", "snapshotDate", "2026-10-01"));
		JsonNode cost = client.post("/analyze", Json.object("document", document)).json().path("cost");
		assertThat(cost.path("pricing").path("snapshotDate").asString()).isEqualTo("2026-10-01");
		assertThat(cost.path("pricing").path("sha256").asString()).isEqualTo(sources.get(1).path("sha256").asString());
	}

	/**
	 * Black-box form of
	 * {@code test_api_run_matches_the_engine_digest_with_auto_sized_parts}: the Python
	 * test compares with the engine called in-process; here two projects of the same
	 * input must give the same digests, scenario or not, with auto-sized parts.
	 */
	@Test
	void run_sameInputInTwoProjects_givesSameDigests(ApiClient client) {
		ObjectNode document = Examples.starter(5, 1);
		String first = Json.id(Runs.createProject(client, document));
		String second = Json.id(Runs.createProject(client, document));
		sameDigests(client, first, second, new RunInput(document, null));
		sameDigests(client, first, second, new RunInput(document, "three-api-replicas"));
	}

	private static void sameDigests(ApiClient client, String first, String second, RunInput input) {
		JsonNode firstReport = finishedReport(client, first, input);
		JsonNode secondReport = finishedReport(client, second, input);
		String scenario = String.valueOf(input.scenarioId());
		assertThat(firstReport.path("provenance").path("resultDigest")).as(scenario)
			.isEqualTo(secondReport.path("provenance").path("resultDigest"));
		assertThat(firstReport.path("provenance").path("inputDigest")).as(scenario)
			.isEqualTo(secondReport.path("provenance").path("inputDigest"));
		JsonNode cpuCores = firstReport.path("sizing").path("nodes").path("api").path("specs").path("cpuCores");
		assertThat(cpuCores.path("source").asString()).isEqualTo("auto");
	}

	private static JsonNode finishedReport(ApiClient client, String projectId, RunInput input) {
		ObjectNode body = Json.object("document", input.document(), "scenarioId", input.scenarioId());
		JsonNode run = client.post("/projects/" + projectId + "/runs", body).json();
		assertThat(Runs.waitFor(client, Json.id(run)).path("status").asString()).isEqualTo("succeeded");
		return client.get("/runs/" + Json.id(run) + "/report").json();
	}

	@Test
	void projectCrud_withStaleRevision_isConflict(ApiClient client) {
		Workspaces.enter(client);
		JsonNode created = Runs.createProject(client);
		String id = Json.id(created);
		assertThat(created.path("name").asString()).isEqualTo("Cache-aside web API (starter)");
		assertThat(created.path("nodeCount").asInt()).isEqualTo(5);
		assertThat(created.path("revision").asInt()).isEqualTo(1);
		ObjectNode document = Json.copy(created.path("document"));
		JsonNode api = Json.find(document.path("architecture").path("nodes"), "id", "api");
		((ObjectNode) api.path("config")).put("replicas", 5);
		ApiResponse updated = client.put("/projects/" + id,
				Json.object("name", "My system", "document", document, "expectedRevision", 1));
		assertThat(updated.status()).as(updated.text()).isEqualTo(200);
		JsonNode body = updated.json();
		assertThat(body.path("revision").asInt()).isEqualTo(2);
		assertThat(body.path("name").asString()).isEqualTo("My system");
		assertThat(body.path("document").path("metadata").path("name").asString()).isEqualTo("My system");
		ApiResponse stale = client.put("/projects/" + id, Json.object("document", document, "expectedRevision", 1));
		assertThat(stale.status()).isEqualTo(409);
		assertThat(Json.strings(client.get("/projects").json(), "id")).containsExactly(id);
		assertThat(client.post("/projects/" + id + "/duplicate").json().path("name").asString())
			.isEqualTo("My system (copy)");
		assertThat(client.delete("/projects/" + id).status()).isEqualTo(204);
		assertThat(client.get("/projects/" + id).status()).isEqualTo(404);
	}

	@Test
	void create_fromExampleOrBlank_makesProject(ApiClient client) {
		JsonNode fromExample = client.post("/projects", Json.object("exampleId", "queueing-lab")).json();
		assertThat(fromExample.path("scenarioCount").asInt()).isEqualTo(4);
		JsonNode blank = client.post("/projects", Json.object("name", "Blank")).json();
		assertThat(blank.path("nodeCount").asInt()).isZero();
		assertThat(blank.path("name").asString()).isEqualTo("Blank");
		assertThat(client.post("/projects", Json.object("exampleId", "nope")).status()).isEqualTo(404);
	}

	@Test
	void create_sameTemplateTwice_reusesUneditedCopy(ApiClient client) {
		Workspaces.enter(client);
		ObjectNode body = Json.object("exampleId", "queueing-lab", "reuseUnedited", true);
		ApiResponse first = client.post("/projects", body);
		assertThat(first.status()).isEqualTo(201);
		ApiResponse again = client.post("/projects", body);
		assertThat(again.status()).isEqualTo(200);
		assertThat(again.json().path("id")).isEqualTo(first.json().path("id"));
		// Without the flag it is an explicit new project.
		ApiResponse plain = client.post("/projects", Json.object("exampleId", "queueing-lab"));
		assertThat(plain.status()).isEqualTo(201);
		assertThat(plain.json().path("id")).isNotEqualTo(first.json().path("id"));
		// A project the user named is theirs: never handed back as "the template".
		ApiResponse named = client.post("/projects", body.deepCopy().put("name", "Mine"));
		assertThat(named.status()).isEqualTo(201);
		assertThat(named.json().path("name").asString()).isEqualTo("Mine");
		// A saved copy belongs to the user too; the template opens an unedited copy.
		for (JsonNode copy : List.of(first.json(), plain.json())) {
			JsonNode saved = client.put("/projects/" + Json.id(copy), Json.object("document", copy.path("document")))
				.json();
			assertThat(saved.path("revision").asInt()).isEqualTo(2);
		}
		ApiResponse fresh = client.post("/projects", body);
		assertThat(fresh.status()).isEqualTo(201);
		assertThat(Json.id(fresh.json())).isNotIn(Json.id(first.json()), Json.id(plain.json()), Json.id(named.json()));
		// A duplicate is the user's own project, not a copy of the template.
		String freshId = Json.id(fresh.json());
		JsonNode duplicate = client.post("/projects/" + freshId + "/duplicate").json();
		client.delete("/projects/" + freshId);
		assertThat(client.post("/projects", body).json().path("id")).isNotEqualTo(duplicate.path("id"));
	}

	@Test
	void update_withWorkInProgressGraph_savesButDoesNotSimulate(ApiClient client) {
		JsonNode project = Runs.createProject(client);
		String id = Json.id(project);
		ObjectNode document = Json.copy(project.path("document"));
		// graph-level errors: allowed when saving
		((ObjectNode) document.path("architecture")).set("edges", Json.array());
		assertThat(client.put("/projects/" + id, Json.object("document", document)).status()).isEqualTo(200);
		ApiResponse run = client.post("/projects/" + id + "/runs", Json.object());
		assertThat(run.status()).isEqualTo(422);
		JsonNode detail = run.json().path("detail");
		assertThat(detail.path("message").asString()).startsWith("Cannot simulate");
		assertThat(Json.strings(detail.path("issues"), "code")).contains("port.missing_link");
		// schema-level errors are rejected on save
		((ObjectNode) document.path("architecture")).set("edges", Json
			.array(Json.object("id", "x", "source", "a", "target", "b", "config", Json.object("maxAttempts", 99))));
		assertThat(client.put("/projects/" + id, Json.object("document", document)).status()).isEqualTo(422);
	}

	@Test
	void importExport_ofStarter_roundTrips(ApiClient client) {
		ApiResponse imported = client.post("/projects/import", Examples.starter());
		assertThat(imported.status()).isEqualTo(201);
		String id = Json.id(imported.json());
		ApiResponse exported = client.get("/projects/" + id + "/export");
		assertThat(exported.header("content-disposition"))
			.hasValueSatisfying((disposition) -> assertThat(disposition).contains("attachment"));
		JsonNode again = client.post("/projects/import", exported.json()).json();
		assertThat(again.path("document")).isEqualTo(exported.json());
		ObjectNode noVersion = Examples.starter();
		noVersion.remove("schemaVersion");
		ApiResponse response = client.post("/projects/import", noVersion);
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("schemaVersion");
	}

	@Test
	void run_toCompletion_reportsAndReproduces(ApiClient client) {
		String url = "/projects/" + Json.id(Runs.createProject(client)) + "/runs";
		ApiResponse response = client.post(url, Json.object("scenarioId", "three-api-replicas"));
		assertThat(response.status()).as(response.text()).isEqualTo(202);
		JsonNode queued = response.json();
		assertThat(queued.path("status").asString()).isIn("queued", "running");
		assertThat(queued.path("label").asString()).isEqualTo("3 API replicas");
		JsonNode done = Runs.waitFor(client, Json.id(queued));
		assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("succeeded");
		assertThat(done.path("progress").asDouble()).isEqualTo(1.0);
		assertThat(done.path("summary").path("throughputRps").asDouble()).isGreaterThan(300);
		assertThat(done.path("resultDigest").asString()).isNotEmpty();
		assertThat(done.path("inputDigest").asString()).isNotEmpty();
		String doneId = Json.id(done);

		JsonNode report = client.get("/runs/" + doneId + "/report").json();
		assertThat(report.path("provenance").path("scenarioId").asString()).isEqualTo("three-api-replicas");
		assertThat(report.path("provenance").path("resultDigest")).isEqualTo(done.path("resultDigest"));
		assertThat(Json.strings(report.path("nodes"), "id")).containsExactlyInAnyOrder("clients", "lb", "api", "cache",
				"db");
		assertThat(report.path("timeseries").path("throughputRps").isEmpty()).isFalse();
		assertThat(report.path("assumptions").path("modelNotes").isEmpty()).isFalse();

		// reproduce from the stored snapshot -> identical results
		JsonNode rerun = client.post("/runs/" + doneId + "/rerun").json();
		assertThat(rerun.path("reproducesRunId").asString()).isEqualTo(doneId);
		JsonNode again = Runs.waitFor(client, Json.id(rerun));
		assertThat(again.path("resultDigest")).isEqualTo(done.path("resultDigest"));
		assertThat(again.path("inputDigest")).isEqualTo(done.path("inputDigest"));

		assertThat(Json.strings(client.get(url).json(), "id")).containsExactlyInAnyOrder(doneId, Json.id(again));
		assertThat(client.get("/runs/" + doneId + "/input").json().path("schemaVersion").asString()).isEqualTo("1.11");
	}

	@Test
	void run_withUnsavedDocument_simulatesItWithoutSaving(ApiClient client) {
		JsonNode project = Runs.createProject(client);
		String id = Json.id(project);
		ObjectNode edited = Json.copy(project.path("document"));
		((ObjectNode) edited.path("workload")).put("rps", 50);
		JsonNode run = client.post("/projects/" + id + "/runs", Json.object("document", edited)).json();
		JsonNode done = Runs.waitFor(client, Json.id(run));
		// simulated the edited document...
		assertThat(done.path("summary").path("offeredRps").asDouble()).isLessThan(80);
		JsonNode saved = client.get("/projects/" + id).json();
		// ...without changing the saved one
		assertThat(saved.path("document").path("workload").path("rps").asInt()).isEqualTo(400);
		JsonNode report = client.get("/runs/" + Json.id(done) + "/report").json();
		assertThat(report.path("input").path("workload").path("rps").asInt()).isEqualTo(50);
	}

	@Test
	void batch_ofTwoScenarios_runsAndCompares(ApiClient client) {
		String project = Json.id(Runs.createProject(client));
		ApiResponse response = client.post("/projects/" + project + "/runs/batch",
				Json.object("scenarioIds", Json.array("one-api-replica", "three-api-replicas")));
		assertThat(response.status()).isEqualTo(202);
		JsonNode first = Runs.waitFor(client, Json.id(response.json().get(0)));
		JsonNode second = Runs.waitFor(client, Json.id(response.json().get(1)));
		assertThat(first.path("status").asString()).isEqualTo("succeeded");
		assertThat(second.path("status").asString()).isEqualTo("succeeded");
		JsonNode comparison = client.get(ApiClient.query("/compare", "a", Json.id(first), "b", Json.id(second))).json();
		assertThat(comparison.path("inputDiff")).isEqualTo(Json
			.array(Json.object("path", "node[api].replicas", "label", "API Servers: replicas", "a", 1, "b", 3)));
		assertThat(comparison.path("labels")).isEqualTo(Json.object("a", "1 API replica", "b", "3 API replicas"));
		assertThat(Json.items(comparison.path("explanations")))
			.anyMatch((explanation) -> explanation.asString().contains("no longer saturated"));
		ApiResponse bad = client.post("/projects/" + project + "/runs/batch",
				Json.object("scenarioIds", Json.array("nope")));
		assertThat(bad.status()).isEqualTo(422);
	}

	@Test
	void delete_projectWithRuns_removesItsRuns(ApiClient client) {
		String project = Json.id(Runs.createProject(client));
		String run = Runs.startRun(client, project);
		Runs.waitFor(client, run);
		client.delete("/projects/" + project);
		assertThat(client.get("/runs/" + run).status()).isEqualTo(404);
	}

	@Test
	void run_withSeedPast32Bits_roundTrips(ApiClient client) {
		// seeds reach 2**32 - 1, past a 32-bit INTEGER column (matters on PostgreSQL)
		ObjectNode document = Examples.starter(3, 1);
		((ObjectNode) document.path("workload")).put("seed", 4_000_000_000L);
		String project = Json.id(Runs.createProject(client, document));
		String run = Runs.startRun(client, project);
		JsonNode done = Runs.waitFor(client, run);
		assertThat(done.path("status").asString()).isEqualTo("succeeded");
		assertThat(done.path("seed").asLong()).isEqualTo(4_000_000_000L);
	}

	/** Runs over the server's request limit (small-runs: 1,000) are refused. */
	@Test
	void run_overTheRequestLimit_isRefused(@On(Profile.SMALL_RUNS) ApiClient client) {
		String project = Json.id(Runs.createProject(client));
		ApiResponse response = client.post("/projects/" + project + "/runs", Json.object());
		assertThat(response.status()).isEqualTo(422);
		assertThat(response.message()).contains("Reduce RPS or duration");
	}

	/**
	 * One run at a time (constrained profile): the second waits, and both cancel. The
	 * starter at 400 requests a second for 3,600 s, the most a design may ask for, is
	 * about 1.4 million requests: under the server's limit (2 million) and tens of
	 * seconds' work for the Java simulator, so the first run is still running when it is
	 * cancelled.
	 */
	@Test
	void cancel_queuedAndRunningRuns_cancelsBoth(@On(Profile.CONSTRAINED) ApiClient client) {
		String project = Json.id(Runs.createProject(client, Examples.starter(3600, 5)));
		String first = Runs.startRun(client, project);
		String second = Runs.startRun(client, project);
		// cancel the queued one immediately and the running one cooperatively
		assertThat(client.post("/runs/" + second + "/cancel").json().path("status").asString()).isEqualTo("cancelled");
		long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
		while (client.get("/runs/" + first).json().path("status").asString().equals("queued")) {
			assertThat(System.nanoTime()).isLessThan(deadline);
			Runs.pause(Duration.ofMillis(20));
		}
		client.post("/runs/" + first + "/cancel");
		assertThat(Runs.waitFor(client, first).path("status").asString()).isEqualTo("cancelled");
		assertThat(client.get("/runs/" + first + "/report").status()).isEqualTo(409);
	}

	/** Bodies over the server's limit (constrained: 10,000 bytes) are refused. */
	@Test
	void import_withBodyOverTheLimit_isRefused(@On(Profile.CONSTRAINED) ApiClient client) {
		ObjectNode padded = Examples.starter();
		padded.put("pad", "x".repeat(20_000));
		assertThat(client.post("/projects/import", padded).status()).isEqualTo(413);
	}

	/** What a run starts from: a document and, or not, one of its scenarios. */
	private record RunInput(JsonNode document, @Nullable String scenarioId) {
	}

}
