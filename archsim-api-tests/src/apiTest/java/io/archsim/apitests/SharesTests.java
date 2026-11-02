package io.archsim.apitests;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Share links: a frozen copy of one run's report anyone with the link can read, without
 * traces or telemetry addresses; 404 once revoked or expired; who may make, list and
 * revoke them; the per-address limit on the public page; and "Open a copy". From {@code
 * services/api/tests/test_api_shares.py}.
 *
 * <p>
 * The Python tests write finished runs straight to the database, with a calibration that
 * names a Prometheus address. Here the runs are real short simulations of a design that
 * carries such a calibration: the engine copies it into the report's assumptions, so the
 * share has an address to hide. Tests with other people run on the hosted server, with
 * real people; the Python tests' monkeypatched limit of 3 views is the real 60 here.
 *
 * <p>
 * Not ported: {@code test_new_slugs_are_random_128_bit_base62} and {@code
 * test_traces_and_addresses_are_left_out_of_the_public_report}, unit tests of the Python
 * functions {@code new_slug} and {@code public_report} (what they make is checked through
 * the API above: the slug's form, no traces, hidden addresses).
 */
@ApiTest(Profile.LOCAL)
class SharesTests {

	/** Public share answers one address gets a minute ({@code PUBLIC_VIEWS_PER_IP}). */
	private static final int PUBLIC_VIEWS_PER_MINUTE = 60;

	// -- the snapshot
	@Test
	void share_ofFinishedRun_isFrozenCopyWithoutTraces(ApiClient client) {
		Workspaces.enter(client);
		JsonNode project = Runs.createProject(client, Examples.starter(4, 1));
		String projectId = id(project);
		String runId = Shares.finishedRun(client, projectId);
		JsonNode full = client.get("/runs/" + runId + "/report").json();
		// the stored report has them; the share must not
		assertThat(full.path("traces").isEmpty()).isFalse();
		JsonNode made = share(client, runId, Json.object());
		String slug = slug(made);
		assertShareOf(made, full, project);

		ApiResponse got = publicShare(client, slug);
		assertThat(got.status()).isEqualTo(200);
		JsonNode body = got.json();
		assertPublicCopyOf(body, full, project);

		// frozen: changing the design, deleting the run and then the project
		// changes nothing
		client.put("/projects/" + projectId, Json.object("name", "Renamed"));
		assertThat(client.delete("/runs/" + runId).status()).isEqualTo(204);
		assertThat(publicShare(client, slug).json().path("report")).isEqualTo(body.path("report"));
		assertThat(client.delete("/projects/" + projectId).status()).isEqualTo(204);
		JsonNode again = publicShare(client, slug).json();
		assertThat(again.path("report")).isEqualTo(body.path("report"));
		assertThat(again.path("projectName")).isEqualTo(project.path("name"));
		// still listed, in its workspace
		JsonNode listed = client.get("/shares").json();
		assertThat(Json.items(listed))
			.extracting(
					(entry) -> entry.path("id").asString() + " " + entry.path("projectId") + " " + entry.path("runId"))
			.containsExactly(id(made) + " null null");
	}

	/** A new share of the run whose report is {@code full}, in {@code project}. */
	private static void assertShareOf(JsonNode made, JsonNode full, JsonNode project) {
		String slug = slug(made);
		assertThat(slug).matches("[0-9A-Za-z]{22}");
		assertThat(made.path("path").asString()).isEqualTo("/r/" + slug);
		// no public URL
		assertThat(made.path("url").isNull()).isTrue();
		assertThat(made.path("status").asString()).isEqualTo("active");
		assertThat(made.path("viewCount").asInt()).isZero();
		assertThat(made.path("resultDigest")).isEqualTo(full.path("provenance").path("resultDigest"));
		assertThat(made.path("reportVersion")).isEqualTo(full.path("reportVersion"));
		assertThat(made.path("title")).isEqualTo(project.path("name"));
		// 30 days by default
		assertThat(made.path("expiresAt").isString()).isTrue();
	}

	/** The public page of a share of the run whose report is {@code full}. */
	private static void assertPublicCopyOf(JsonNode body, JsonNode full, JsonNode project) {
		assertThat(body.path("report").has("traces")).isFalse();
		// the design stays behind; "Open a copy" uses it
		assertThat(body.has("document")).isFalse();
		assertThat(body.path("report").path("summary")).isEqualTo(full.path("summary"));
		assertThat(body.path("objectives")).isEqualTo(project.path("document").path("objectives"));
		assertThat(body.path("projectName")).isEqualTo(project.path("name"));
		assertThat(body.path("runLabel").asString()).isEqualTo("Current design");
		assertThat(body.path("canCopy").asBoolean()).isTrue();
	}

	@Test
	void share_ofCalibratedRun_hidesTelemetryAddresses(ApiServer server) {
		ApiClient client = server.client();
		String runId = Shares.finishedRun(client, Shares.calibratedProject(client));
		// the run's own report names the address: the engine copies the design's
		// calibration
		assertThat(Shares.calibrationSource(client.get("/runs/" + runId + "/report").json()))
			.isEqualTo(Shares.PROMETHEUS);
		JsonNode made = share(client, runId, Json.object());
		JsonNode body = publicShare(client, slug(made)).json();
		assertThat(Shares.calibrationSource(body.path("report"))).isEqualTo("Prometheus (address hidden)");
		Object payload = server.db().value("SELECT payload::text FROM shared_reports WHERE id = ?", id(made));
		assertThat(payload).isNotNull();
		assertThat(String.valueOf(payload)).doesNotContain("hunter2").doesNotContain("prom.internal");
	}

	@Test
	void share_withTitleAndExpiry_keepsThem(ApiClient client) {
		String runId = Shares.finishedRun(client, Shares.calibratedProject(client));
		JsonNode never = share(client, runId,
				Json.object("title", "  For the platform review  ", "expiresInDays", null));
		assertThat(never.path("title").asString()).isEqualTo("For the platform review");
		assertThat(never.path("expiresAt").isNull()).isTrue();
		assertThat(publicShare(client, slug(never)).json().path("title").asString())
			.isEqualTo("For the platform review");
		ApiResponse bad = client.post("/runs/" + runId + "/shares", Json.object("expiresInDays", 0));
		assertThat(bad.status()).isEqualTo(422);
	}

	/**
	 * The Python test's run stays queued (no worker); here it is a long run, then
	 * cancelled.
	 */
	@Test
	void share_ofUnfinishedRun_isConflict(ApiClient client) {
		String unfinished = Shares.startLongRun(client, Shares.calibratedProject(client));
		ApiResponse res = client.post("/runs/" + unfinished + "/shares", Json.object());
		assertThat(res.status()).isEqualTo(409);
		assertThat(res.message()).contains("only finished runs");
		client.post("/runs/" + unfinished + "/cancel");
		Runs.waitFor(client, unfinished);
	}

	// -- 404 once revoked or expired
	@Test
	void revoke_activeShare_answers404AtOnceAndDeletesTheCopy(ApiServer server) {
		ApiClient client = server.client();
		String projectId = Shares.calibratedProject(client);
		JsonNode made = share(client, Shares.finishedRun(client, projectId), Json.object());
		assertThat(publicShare(client, slug(made)).status()).isEqualTo(200);
		assertThat(client.delete("/shares/" + id(made)).status()).isEqualTo(204);
		ApiResponse gone = publicShare(client, slug(made));
		assertThat(gone.status()).isEqualTo(404);
		assertThat(gone.message()).isEqualTo("Share link not found");
		// cleared: SQL NULL, or (as the Python API stores it, through SQLAlchemy's JSON
		// type) a JSON null
		List<Map<String, @Nullable Object>> rows = server.db()
			.rows("SELECT (payload IS NULL OR jsonb_typeof(payload) = 'null') AS cleared,"
					+ " revoked_at IS NOT NULL AS revoked FROM shared_reports WHERE id = ?", id(made));
		assertThat(rows).hasSize(1);
		assertThat(rows.get(0)).containsEntry("cleared", true).containsEntry("revoked", true);
		assertThat(client.delete("/shares/" + id(made)).status()).isEqualTo(204); // idempotent
		JsonNode listed = client.get("/projects/" + projectId + "/shares").json();
		assertThat(Json.strings(listed, "status")).containsExactly("revoked");
		// the project's history names the link once when made and once when revoked
		List<String> actions = Json.strings(client.get("/projects/" + projectId + "/activity").json().path("items"),
				"action");
		assertThat(actions).containsOnlyOnce("share.create", "share.revoke");
		// newest first
		assertThat(actions.indexOf("share.revoke")).isLessThan(actions.indexOf("share.create"));
	}

	@Test
	void publicShare_whenExpired_is404(ApiServer server) {
		ApiClient client = server.client();
		String projectId = Shares.calibratedProject(client);
		JsonNode made = share(client, Shares.finishedRun(client, projectId), Json.object("expiresInDays", 1));
		server.db().update("UPDATE shared_reports SET expires_at = now() - interval '1 second' WHERE id = ?", id(made));
		assertThat(publicShare(client, slug(made)).status()).isEqualTo(404);
		assertThat(client.get("/projects/" + projectId + "/shares").json().path(0).path("status").asString())
			.isEqualTo("expired");
	}

	@Test
	void publicShare_withUnknownOrMalformedSlug_isTheSame404(ApiClient client) {
		for (String slug : List.of("0".repeat(22), "short", "x".repeat(23), "not-base62-but-22-chr!")) {
			ApiResponse res = publicShare(client, slug);
			assertThat(res.status()).as(slug).isEqualTo(404);
			assertThat(res.message()).as(slug).isEqualTo("Share link not found");
		}
	}

	@Test
	void publicShare_ofActiveLink_isNeitherCachedNorIndexed(ApiClient client) {
		JsonNode made = share(client, Shares.finishedRun(client, Shares.calibratedProject(client)), Json.object());
		ApiResponse res = publicShare(client, slug(made));
		assertThat(res.header("cache-control")).isEqualTo("no-store");
		assertThat(res.header("x-robots-tag")).isEqualTo("noindex");
	}

	@Test
	void publicShare_viewedOrPreviewed_countsOnlyViews(ApiClient client) {
		String projectId = Shares.calibratedProject(client);
		JsonNode made = share(client, Shares.finishedRun(client, projectId), Json.object());
		for (int i = 0; i < 3; i++) {
			publicShare(client, slug(made));
		}
		// a link preview
		client.get(ApiClient.query("/public/shares/" + slug(made), "view", "false"));
		JsonNode listed = client.get("/projects/" + projectId + "/shares").json();
		assertThat(listed.path(0).path("viewCount").asInt()).isEqualTo(3);
	}

	// -- roles
	@Test
	void shares_byRole_onlyEditorsMakeOrRevokeThem(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.enter(owner);
		String projectId = Shares.calibratedProject(owner);
		String runId = Shares.finishedRun(owner, projectId);
		JsonNode made = share(owner, runId, Json.object());
		ApiClient vera = server.signedIn("vera");
		Workspaces.join(owner, team, vera, "viewer");
		ApiClient ed = server.signedIn("ed");
		Workspaces.join(owner, team, ed, "editor");

		assertThat(vera.get("/projects/" + projectId + "/shares").json().size()).isEqualTo(1);
		assertThat(vera.post("/runs/" + runId + "/shares", Json.object()).status()).isEqualTo(403);
		assertThat(vera.delete("/shares/" + id(made)).status()).isEqualTo(403);

		assertThat(ed.post("/runs/" + runId + "/shares", Json.object()).status()).isEqualTo(201);
		assertThat(ed.delete("/shares/" + id(made)).status()).isEqualTo(204);

		// a read token is a viewer
		ApiClient edsReadToken = Shares.tokenClient(server, ed, "read");
		assertThat(edsReadToken.post("/runs/" + runId + "/shares", Json.object()).status()).isEqualTo(403);
	}

	@Test
	void shares_fromOutsideTheWorkspace_revealNothing(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String projectId = Shares.calibratedProject(owner);
		String runId = Shares.finishedRun(owner, projectId);
		JsonNode made = share(owner, runId, Json.object());
		ApiClient olga = server.signedIn("olga"); // no role in the owner's workspace

		assertThat(olga.post("/runs/" + runId + "/shares", Json.object()).status()).isEqualTo(404);
		assertThat(olga.get("/projects/" + projectId + "/shares").status()).isEqualTo(404);
		ApiResponse res = olga.delete("/shares/" + id(made));
		assertThat(res.status()).isEqualTo(404);
		assertThat(res.message()).isEqualTo("Share link not found");
		assertThat(olga.delete("/shares/nope").status()).isEqualTo(404);
		// the link itself is public
		assertThat(publicShare(olga, slug(made)).status()).isEqualTo(200);
	}

	@Test
	void publicShare_onSignedInServer_servesAnyoneWithTheLink(@On(Profile.HOSTED) ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		String workspace = Workspaces.first(ada);
		ApiResponse res = ada.post(ApiClient.query("/projects", "workspace", workspace),
				Json.object("document", Shares.calibrated()));
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		String projectId = id(res.json());
		String runId = Shares.finishedRun(ada, projectId); // the run belongs to ada's
															// workspace
		JsonNode made = share(ada, runId, Json.object());

		ApiClient anonymous = server.client();
		assertThat(anonymous.get("/projects/" + projectId + "/shares").status()).isEqualTo(401);
		assertThat(anonymous.post("/runs/" + runId + "/shares", Json.object()).status()).isEqualTo(401);
		assertThat(publicShare(anonymous, slug(made)).status()).isEqualTo(200);
		// copying needs a sign-in: the copy lands in your own workspace
		ApiResponse copy = anonymous.post("/projects/from-share", Json.object("slug", slug(made)));
		assertThat(copy.status()).isEqualTo(401);
	}

	// -- the per-address limit
	/**
	 * The rate-limited profile has the hosted limits: 60 public views a minute per
	 * address.
	 */
	@Test
	void publicShare_pastViewsPerAddress_is429(@On(Profile.RATE_LIMITED) ApiServer server) {
		ApiClient client = server.client();
		String slug = slug(share(client, Shares.finishedRun(client, Shares.calibratedProject(client)), Json.object()));
		Shares.awaitRoomInTheMinute();
		for (int i = 1; i <= PUBLIC_VIEWS_PER_MINUTE; i++) {
			assertThat(publicShare(client, slug).status()).as("view %d", i).isEqualTo(200);
		}
		assertThat(publicShare(client, slug).status()).isEqualTo(429);
		ApiResponse refused = publicShare(client, slug);
		assertThat(refused.status()).isEqualTo(429);
		assertThat(Integer.parseInt(Objects.requireNonNull(refused.header("retry-after")))).isGreaterThanOrEqualTo(1);
		// guessing links counts too
		assertThat(publicShare(client, "0".repeat(22)).status()).isEqualTo(429);
		// another visitor, behind the web server: a client with its own
		// X-ArchSim-Client-IP
		assertThat(publicShare(server.client(), slug).status()).isEqualTo(200);
	}

	@Test
	void publicShare_withoutLimits_isNeverRefused(ApiClient client) {
		String slug = slug(share(client, Shares.finishedRun(client, Shares.calibratedProject(client)), Json.object()));
		for (int i = 1; i <= PUBLIC_VIEWS_PER_MINUTE + 1; i++) {
			assertThat(publicShare(client, slug).status()).as("view %d", i).isEqualTo(200);
		}
	}

	// -- open a copy
	@Test
	void openACopy_ofShare_makesProjectFromTheSharedDesign(@On(Profile.HOSTED) ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		JsonNode project = Runs.createProject(owner, Shares.calibrated());
		JsonNode made = share(owner, Shares.finishedRun(owner, id(project)), Json.object());
		String team = id(Workspaces.create(owner, "Mine"));
		ApiResponse res = owner.post(ApiClient.query("/projects/from-share", "workspace", team),
				Json.object("slug", slug(made)));
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		JsonNode copied = res.json();
		assertThat(copied.path("name").asString()).isEqualTo(project.path("name").asString() + " (copy)");
		assertThat(copied.path("document").path("architecture"))
			.isEqualTo(project.path("document").path("architecture"));
		assertThat(Json.write(copied.path("document"))).doesNotContain("hunter2");
		JsonNode inTeam = owner.get(ApiClient.query("/projects", "workspace", team)).json();
		assertThat(Json.strings(inTeam, "id")).containsExactly(id(copied));
		ApiResponse named = owner.post("/projects/from-share", Json.object("slug", slug(made), "name", "Fork"));
		assertThat(named.status()).isEqualTo(201);
		assertThat(named.json().path("name").asString()).isEqualTo("Fork");

		ApiClient vera = server.signedIn("vera");
		Workspaces.join(owner, team, vera, "viewer");
		// a viewer cannot add projects to the workspace
		assertThat(
				vera.post(ApiClient.query("/projects/from-share", "workspace", team), Json.object("slug", slug(made)))
					.status())
			.isEqualTo(403);
		owner.delete("/shares/" + id(made));
		ApiResponse gone = owner.post("/projects/from-share", Json.object("slug", slug(made)));
		assertThat(gone.status()).isEqualTo(404);
	}

	// -- helpers
	private static JsonNode share(ApiClient client, String runId, ObjectNode body) {
		ApiResponse res = client.post("/runs/" + runId + "/shares", body);
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		return res.json();
	}

	private static ApiResponse publicShare(ApiClient client, String slug) {
		return client.get("/public/shares/" + slug);
	}

	private static String id(JsonNode node) {
		return node.path("id").asString();
	}

	private static String slug(JsonNode share) {
		return share.path("slug").asString();
	}

}
