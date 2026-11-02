package io.archsim.apitests;

import static io.archsim.apitests.Json.items;
import static io.archsim.apitests.Json.object;
import static io.archsim.apitests.Json.strings;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Projects, persistence, import and export, runs, reports and comparison, without
 * sign-in. From {@code services/api/tests/test_api_endpoints.py}.
 */
@ApiTest(Profile.LOCAL)
class EndpointsTest {

	@Test
	void metaCatalogExamplesAndSchema(ApiClient client) {
		assertThat(client.get("/health").json()).isEqualTo(object("status", "ok", "database", "ok"));
		JsonNode meta = client.get("/meta").json();
		assertThat(meta.path("engineVersion").asString()).isNotEmpty();
		assertThat(meta.path("schemaVersion").asString()).isEqualTo("1.7");
		assertThat(meta.path("database").asString()).isIn("sqlite", "postgresql");
		JsonNode catalog = client.get("/components").json();
		assertThat(strings(catalog.path("components"), "type")).contains("traffic_source", "load_balancer", "service",
				"cache", "database");
		assertThat(strings(catalog.path("edgeFields"), "key")).contains("latencyMs");
		JsonNode examples = client.get("/examples").json();
		assertThat(strings(examples, "id")).contains("cache-aside-starter", "queueing-lab", "retry-storm-ttl-cache");
		assertThat(client.get("/examples/cache-aside-starter").json().path("schemaVersion").asString())
			.isEqualTo("1.3");
		assertThat(client.get("/examples/nope").status()).isEqualTo(404);
		assertThat(client.get("/schema/architecture").json().path("title").asString())
			.isEqualTo("ArchitectureDocument");
		assertThat(client.get("/openapi.json").json().path("paths").has("/api/v1/projects")).isTrue();
	}

	@Test
	void validateReportsIssues(ApiClient client) {
		JsonNode good = client.post("/validate", object("document", Examples.starter())).json();
		assertThat(good).isEqualTo(object("valid", true, "issues", Json.array()));
		// cache misses that nothing reads: the API no longer reads the DB on a miss
		ObjectNode bad = Examples.starter();
		for (JsonNode edge : bad.path("architecture").path("edges")) {
			if (edge.path("id").asString().equals("api-db")) {
				((ObjectNode) edge.path("config")).put("readsOnMiss", false);
			}
		}
		JsonNode res = client.post("/validate", object("document", bad)).json();
		assertThat(res.path("valid").asBoolean()).isFalse();
		assertThat(res.path("issues").get(0).path("code").asString()).isEqualTo("cache.miss_unrouted");
		assertThat(res.path("issues").get(0).path("nodeId").asString()).isEqualTo("cache");
		JsonNode scenario = client.post("/validate", object("document", Examples.starter(), "scenarioId", "nope"))
			.json();
		assertThat(scenario.path("issues").get(0).path("code").asString()).isEqualTo("scenario.unknown");
	}

	@Test
	void analyzeReturnsSizesCostAndReview(ApiClient client) {
		ApiResponse res = client.post("/analyze", object("document", Examples.starter()));
		assertThat(res.status()).isEqualTo(200);
		JsonNode a = res.json();
		JsonNode api = a.path("sizing").path("nodes").path("api");
		assertThat(api.path("specs").path("cpuCores").path("source").asString()).isEqualTo("auto");
		assertThat(api.path("auto").path("cpuCores").asDouble()).isGreaterThanOrEqualTo(1);
		assertThat(api.path("pricePerHour").asDouble()).isPositive();
		assertThat(api.path("why").path("cpuCores").asString()).contains("req/s per replica");
		assertThat(a.path("cost").path("monthly").asDouble()).isPositive();
		assertThat(a.path("cost").path("byNode").isEmpty()).isFalse();
		assertThat(strings(a.path("review"), "rule")).isNotEmpty();
		int counted = a.path("reviewCounts").valueStream().mapToInt(JsonNode::asInt).sum();
		assertThat(counted).isEqualTo(a.path("review").size());
		assertThat(a.path("peakRps").asDouble()).isEqualTo(Examples.starter().path("workload").path("rps").asDouble());

		JsonNode sc = client
			.post("/analyze", object("document", Examples.starter(), "scenarioId", "three-api-replicas"))
			.json();
		assertThat(sc.path("scenarioId").asString()).isEqualTo("three-api-replicas");
		// the scenario runs on the design's sizes: same auto size, more replicas billed
		assertThat(sc.path("sizing").path("nodes").path("api").path("auto")).isEqualTo(api.path("auto"));
		assertThat(sc.path("cost").path("monthly").asDouble()).isGreaterThan(a.path("cost").path("monthly").asDouble());
		ApiResponse bad = client.post("/analyze", object("document", object("kind", "nope")));
		assertThat(bad.status()).isEqualTo(422);
		assertThat(bad.json().path("detail").path("issues").isEmpty()).isFalse();
	}

	/**
	 * The local profile adds two snapshots of a made-up provider (see
	 * {@link Profile#LOCAL}).
	 */
	@Test
	void pricingListsEverySnapshotAndAPinnedOneIsUsed(ApiClient client) {
		JsonNode listed = client.get("/pricing").json().path("sources");
		assertThat(listed.get(0).path("provider").asString()).isEqualTo("generic");
		// the AWS snapshot the engine ships is listed too
		assertThat(items(listed)).anyMatch(
				s -> s.path("provider").asString().equals("aws") && s.path("region").asString().equals("us-east-1"));
		List<JsonNode> sources = items(listed).stream()
			.filter(s -> s.path("provider").asString().equals("example-cloud"))
			.toList();
		assertThat(sources).extracting(s -> s.path("date").asString() + " " + s.path("newest").asBoolean())
			.containsExactly("2026-11-01 true", "2026-10-01 false");
		assertThat(items(listed).subList(1, listed.size())).allMatch(s -> s.path("sha256").asString().length() == 64);
		ObjectNode doc = Examples.starter();
		doc.set("pricing", object("provider", "example-cloud", "region", "test-1", "snapshotDate", "2026-10-01"));
		JsonNode cost = client.post("/analyze", object("document", doc)).json().path("cost");
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
	void runsOfTheSameInputGiveTheSameDigestsWithAutoSizedParts(ApiClient client) {
		ObjectNode doc = Examples.starter(5, 1);
		String first = Runs.createProject(client, doc).path("id").asString();
		String second = Runs.createProject(client, doc).path("id").asString();
		sameDigests(client, first, second, doc, null);
		sameDigests(client, first, second, doc, "three-api-replicas");
	}

	private static void sameDigests(ApiClient client, String first, String second, JsonNode doc,
			@Nullable String scenario) {
		JsonNode a = finishedReport(client, first, doc, scenario);
		JsonNode b = finishedReport(client, second, doc, scenario);
		assertThat(a.path("provenance").path("resultDigest")).as(String.valueOf(scenario))
			.isEqualTo(b.path("provenance").path("resultDigest"));
		assertThat(a.path("provenance").path("inputDigest")).as(String.valueOf(scenario))
			.isEqualTo(b.path("provenance").path("inputDigest"));
		assertThat(a.path("sizing").path("nodes").path("api").path("specs").path("cpuCores").path("source").asString())
			.isEqualTo("auto");
	}

	private static JsonNode finishedReport(ApiClient client, String projectId, JsonNode doc,
			@Nullable String scenario) {
		JsonNode run = client.post("/projects/" + projectId + "/runs", object("document", doc, "scenarioId", scenario))
			.json();
		assertThat(Runs.waitFor(client, run.path("id").asString()).path("status").asString()).isEqualTo("succeeded");
		return client.get("/runs/" + run.path("id").asString() + "/report").json();
	}

	@Test
	void projectCrudAndRevisionConflict(ApiClient client) {
		Workspaces.enter(client);
		JsonNode p = Runs.createProject(client);
		String id = p.path("id").asString();
		assertThat(p.path("name").asString()).isEqualTo("Cache-aside web API (starter)");
		assertThat(p.path("nodeCount").asInt()).isEqualTo(5);
		assertThat(p.path("revision").asInt()).isEqualTo(1);
		ObjectNode doc = Json.copy(p.path("document"));
		((ObjectNode) Json.find(doc.path("architecture").path("nodes"), "id", "api").path("config")).put("replicas", 5);
		ApiResponse upd = client.put("/projects/" + id,
				object("name", "My system", "document", doc, "expectedRevision", 1));
		assertThat(upd.status()).as(upd.text()).isEqualTo(200);
		JsonNode body = upd.json();
		assertThat(body.path("revision").asInt()).isEqualTo(2);
		assertThat(body.path("name").asString()).isEqualTo("My system");
		assertThat(body.path("document").path("metadata").path("name").asString()).isEqualTo("My system");
		ApiResponse stale = client.put("/projects/" + id, object("document", doc, "expectedRevision", 1));
		assertThat(stale.status()).isEqualTo(409);
		assertThat(strings(client.get("/projects").json(), "id")).containsExactly(id);
		assertThat(client.post("/projects/" + id + "/duplicate").json().path("name").asString())
			.isEqualTo("My system (copy)");
		assertThat(client.delete("/projects/" + id).status()).isEqualTo(204);
		assertThat(client.get("/projects/" + id).status()).isEqualTo(404);
	}

	@Test
	void createFromExampleAndBlank(ApiClient client) {
		JsonNode ex = client.post("/projects", object("exampleId", "queueing-lab")).json();
		assertThat(ex.path("scenarioCount").asInt()).isEqualTo(4);
		JsonNode blank = client.post("/projects", object("name", "Blank")).json();
		assertThat(blank.path("nodeCount").asInt()).isZero();
		assertThat(blank.path("name").asString()).isEqualTo("Blank");
		assertThat(client.post("/projects", object("exampleId", "nope")).status()).isEqualTo(404);
	}

	@Test
	void openingATemplateTwiceReusesTheUneditedCopy(ApiClient client) {
		Workspaces.enter(client);
		ObjectNode body = object("exampleId", "queueing-lab", "reuseUnedited", true);
		ApiResponse first = client.post("/projects", body);
		assertThat(first.status()).isEqualTo(201);
		ApiResponse again = client.post("/projects", body);
		assertThat(again.status()).isEqualTo(200);
		assertThat(again.json().path("id")).isEqualTo(first.json().path("id"));
		// Without the flag it is an explicit new project.
		ApiResponse plain = client.post("/projects", object("exampleId", "queueing-lab"));
		assertThat(plain.status()).isEqualTo(201);
		assertThat(plain.json().path("id")).isNotEqualTo(first.json().path("id"));
		// A project the user named is theirs: never handed back as "the template".
		ApiResponse named = client.post("/projects", body.deepCopy().put("name", "Mine"));
		assertThat(named.status()).isEqualTo(201);
		assertThat(named.json().path("name").asString()).isEqualTo("Mine");
		// Once a copy is saved it belongs to the user too; the template opens an unedited
		// copy.
		for (JsonNode p : List.of(first.json(), plain.json())) {
			JsonNode saved = client.put("/projects/" + p.path("id").asString(), object("document", p.path("document")))
				.json();
			assertThat(saved.path("revision").asInt()).isEqualTo(2);
		}
		ApiResponse fresh = client.post("/projects", body);
		assertThat(fresh.status()).isEqualTo(201);
		assertThat(fresh.json().path("id").asString()).isNotIn(first.json().path("id").asString(),
				plain.json().path("id").asString(), named.json().path("id").asString());
		// A duplicate is the user's own project, not a copy of the template.
		String freshId = fresh.json().path("id").asString();
		JsonNode dup = client.post("/projects/" + freshId + "/duplicate").json();
		client.delete("/projects/" + freshId);
		assertThat(client.post("/projects", body).json().path("id")).isNotEqualTo(dup.path("id"));
	}

	@Test
	void workInProgressGraphsCanBeSavedButNotSimulated(ApiClient client) {
		JsonNode p = Runs.createProject(client);
		String id = p.path("id").asString();
		ObjectNode doc = Json.copy(p.path("document"));
		((ObjectNode) doc.path("architecture")).set("edges", Json.array()); // graph-level
																			// errors:
																			// allowed
																			// when saving
		assertThat(client.put("/projects/" + id, object("document", doc)).status()).isEqualTo(200);
		ApiResponse run = client.post("/projects/" + id + "/runs", object());
		assertThat(run.status()).isEqualTo(422);
		JsonNode detail = run.json().path("detail");
		assertThat(detail.path("message").asString()).startsWith("Cannot simulate");
		assertThat(strings(detail.path("issues"), "code")).contains("port.missing_link");
		// schema-level errors are rejected on save
		((ObjectNode) doc.path("architecture")).set("edges",
				Json.array(object("id", "x", "source", "a", "target", "b", "config", object("maxAttempts", 99))));
		assertThat(client.put("/projects/" + id, object("document", doc)).status()).isEqualTo(422);
	}

	@Test
	void importExportRoundTrip(ApiClient client) {
		ApiResponse imported = client.post("/projects/import", Examples.starter());
		assertThat(imported.status()).isEqualTo(201);
		String id = imported.json().path("id").asString();
		ApiResponse exported = client.get("/projects/" + id + "/export");
		assertThat(exported.header("content-disposition")).contains("attachment");
		JsonNode again = client.post("/projects/import", exported.json()).json();
		assertThat(again.path("document")).isEqualTo(exported.json());
		ObjectNode noVersion = Examples.starter();
		noVersion.remove("schemaVersion");
		ApiResponse res = client.post("/projects/import", noVersion);
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.message()).contains("schemaVersion");
	}

	@Test
	void runLifecycleReportAndReproduce(ApiClient client) {
		String project = Runs.createProject(client).path("id").asString();
		ApiResponse res = client.post("/projects/" + project + "/runs", object("scenarioId", "three-api-replicas"));
		assertThat(res.status()).as(res.text()).isEqualTo(202);
		JsonNode queued = res.json();
		assertThat(queued.path("status").asString()).isIn("queued", "running");
		assertThat(queued.path("label").asString()).isEqualTo("3 API replicas");
		JsonNode done = Runs.waitFor(client, queued.path("id").asString());
		assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("succeeded");
		assertThat(done.path("progress").asDouble()).isEqualTo(1.0);
		assertThat(done.path("summary").path("throughputRps").asDouble()).isGreaterThan(300);
		assertThat(done.path("resultDigest").asString()).isNotEmpty();
		assertThat(done.path("inputDigest").asString()).isNotEmpty();
		String doneId = done.path("id").asString();

		JsonNode report = client.get("/runs/" + doneId + "/report").json();
		assertThat(report.path("provenance").path("scenarioId").asString()).isEqualTo("three-api-replicas");
		assertThat(report.path("provenance").path("resultDigest")).isEqualTo(done.path("resultDigest"));
		assertThat(strings(report.path("nodes"), "id")).containsExactlyInAnyOrder("clients", "lb", "api", "cache",
				"db");
		assertThat(report.path("timeseries").path("throughputRps").isEmpty()).isFalse();
		assertThat(report.path("assumptions").path("modelNotes").isEmpty()).isFalse();

		// reproduce from the stored snapshot -> identical results
		JsonNode rerun = client.post("/runs/" + doneId + "/rerun").json();
		assertThat(rerun.path("reproducesRunId").asString()).isEqualTo(doneId);
		JsonNode again = Runs.waitFor(client, rerun.path("id").asString());
		assertThat(again.path("resultDigest")).isEqualTo(done.path("resultDigest"));
		assertThat(again.path("inputDigest")).isEqualTo(done.path("inputDigest"));

		assertThat(strings(client.get("/projects/" + project + "/runs").json(), "id")).containsExactlyInAnyOrder(doneId,
				again.path("id").asString());
		assertThat(client.get("/runs/" + doneId + "/input").json().path("schemaVersion").asString()).isEqualTo("1.7");
	}

	@Test
	void runWithAnUnsavedDocumentSnapshot(ApiClient client) {
		JsonNode p = Runs.createProject(client);
		String id = p.path("id").asString();
		ObjectNode edited = Json.copy(p.path("document"));
		((ObjectNode) edited.path("workload")).put("rps", 50);
		JsonNode run = client.post("/projects/" + id + "/runs", object("document", edited)).json();
		JsonNode done = Runs.waitFor(client, run.path("id").asString());
		assertThat(done.path("summary").path("offeredRps").asDouble()).isLessThan(80); // simulated
																						// the
																						// edited
																						// document...
		JsonNode saved = client.get("/projects/" + id).json();
		assertThat(saved.path("document").path("workload").path("rps").asInt()).isEqualTo(400); // ...without
																								// changing
																								// the
																								// saved
																								// one
		JsonNode report = client.get("/runs/" + done.path("id").asString() + "/report").json();
		assertThat(report.path("input").path("workload").path("rps").asInt()).isEqualTo(50);
	}

	@Test
	void batchScenariosAndCompare(ApiClient client) {
		String project = Runs.createProject(client).path("id").asString();
		ApiResponse res = client.post("/projects/" + project + "/runs/batch",
				object("scenarioIds", Json.array("one-api-replica", "three-api-replicas")));
		assertThat(res.status()).isEqualTo(202);
		JsonNode a = Runs.waitFor(client, res.json().get(0).path("id").asString());
		JsonNode b = Runs.waitFor(client, res.json().get(1).path("id").asString());
		assertThat(a.path("status").asString()).isEqualTo("succeeded");
		assertThat(b.path("status").asString()).isEqualTo("succeeded");
		JsonNode cmp = client
			.get(ApiClient.query("/compare", "a", a.path("id").asString(), "b", b.path("id").asString()))
			.json();
		assertThat(cmp.path("inputDiff")).isEqualTo(
				Json.array(object("path", "node[api].replicas", "label", "API Servers: replicas", "a", 1, "b", 3)));
		assertThat(cmp.path("labels")).isEqualTo(object("a", "1 API replica", "b", "3 API replicas"));
		assertThat(items(cmp.path("explanations"))).anyMatch(e -> e.asString().contains("no longer saturated"));
		ApiResponse bad = client.post("/projects/" + project + "/runs/batch",
				object("scenarioIds", Json.array("nope")));
		assertThat(bad.status()).isEqualTo(422);
	}

	@Test
	void deletingAProjectRemovesItsRuns(ApiClient client) {
		String project = Runs.createProject(client).path("id").asString();
		String run = client.post("/projects/" + project + "/runs", object()).json().path("id").asString();
		Runs.waitFor(client, run);
		client.delete("/projects/" + project);
		assertThat(client.get("/runs/" + run).status()).isEqualTo(404);
	}

	@Test
	void largeSeedsRoundTrip(ApiClient client) {
		// Seeds go up to 2**32 - 1, beyond a 32-bit INTEGER column (matters on
		// PostgreSQL).
		ObjectNode doc = Examples.starter(3, 1);
		((ObjectNode) doc.path("workload")).put("seed", 4_000_000_000L);
		String project = Runs.createProject(client, doc).path("id").asString();
		String run = client.post("/projects/" + project + "/runs", object()).json().path("id").asString();
		JsonNode done = Runs.waitFor(client, run);
		assertThat(done.path("status").asString()).isEqualTo("succeeded");
		assertThat(done.path("seed").asLong()).isEqualTo(4_000_000_000L);
	}

	/**
	 * Runs over the server's request limit are refused (the small-runs profile allows
	 * 1,000).
	 */
	@Test
	void aRunOverTheRequestLimitIsRefused(@On(Profile.SMALL_RUNS) ApiClient client) {
		String project = Runs.createProject(client).path("id").asString();
		ApiResponse res = client.post("/projects/" + project + "/runs", object());
		assertThat(res.status()).isEqualTo(422);
		assertThat(res.message()).contains("Reduce RPS or duration");
	}

	/**
	 * With one run at a time (the constrained profile), the second waits and both cancel.
	 */
	@Test
	void cancellingAQueuedAndARunningRun(@On(Profile.CONSTRAINED) ApiClient client) {
		String project = Runs.createProject(client, Examples.starter(120, 5)).path("id").asString();
		String first = client.post("/projects/" + project + "/runs", object()).json().path("id").asString();
		String second = client.post("/projects/" + project + "/runs", object()).json().path("id").asString();
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

	/**
	 * Bodies over the server's limit are refused (the constrained profile allows 10,000
	 * bytes).
	 */
	@Test
	void bodiesOverTheLimitAreRefused(@On(Profile.CONSTRAINED) ApiClient client) {
		ObjectNode padded = Examples.starter();
		padded.put("pad", "x".repeat(20_000));
		assertThat(client.post("/projects/import", padded).status()).isEqualTo(413);
	}

}
