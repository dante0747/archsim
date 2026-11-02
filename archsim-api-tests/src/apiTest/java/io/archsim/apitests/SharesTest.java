package io.archsim.apitests;

import static io.archsim.apitests.Json.object;
import static io.archsim.apitests.Json.strings;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Share links: a frozen copy of one run's report anyone with the link can read, without traces
 * or telemetry addresses; 404 once revoked or expired; who may make, list and revoke them; the
 * per-address limit on the public page; and "Open a copy". From {@code
 * services/api/tests/test_api_shares.py}.
 *
 * <p>The Python tests write finished runs straight to the database, with a calibration that
 * names a Prometheus address. Here the runs are real short simulations of a design that carries
 * such a calibration: the engine copies it into the report's assumptions, so the share has an
 * address to hide. Tests with other people run on the hosted server, with real people; the
 * Python tests' monkeypatched limit of 3 views is the real 60 here.
 *
 * <p>Not ported: {@code test_new_slugs_are_random_128_bit_base62} and {@code
 * test_traces_and_addresses_are_left_out_of_the_public_report}, unit tests of the Python
 * functions {@code new_slug} and {@code public_report} (what they make is checked through the
 * API above: the slug's form, no traces, hidden addresses).
 */
@ApiTest(Profile.LOCAL)
class SharesTest {
    private static final String PROMETHEUS = "Prometheus https://scraper:hunter2@prom.internal.example:9090";

    /** Public share answers one address gets a minute ({@code PUBLIC_VIEWS_PER_IP}). */
    private static final int PUBLIC_VIEWS_PER_MINUTE = 60;

    // -- the snapshot ------------------------------------------------------------------------------
    @Test
    void aShareIsAFrozenCopyOfARealRunWithoutTraces(ApiClient client) {
        Workspaces.enter(client);
        JsonNode project = Runs.createProject(client, Examples.starter(4, 1));
        String projectId = id(project);
        String runId = finishedRun(client, projectId);
        JsonNode full = client.get("/runs/" + runId + "/report").json();
        assertThat(full.path("traces").isEmpty()).isFalse(); // the stored report has them; the share must not
        JsonNode made = share(client, runId, object());
        String slug = slug(made);
        assertThat(slug).matches("[0-9A-Za-z]{22}");
        assertThat(made.path("path").asString()).isEqualTo("/r/" + slug);
        assertThat(made.path("url").isNull()).isTrue(); // no public URL
        assertThat(made.path("status").asString()).isEqualTo("active");
        assertThat(made.path("viewCount").asInt()).isZero();
        assertThat(made.path("resultDigest")).isEqualTo(full.path("provenance").path("resultDigest"));
        assertThat(made.path("reportVersion")).isEqualTo(full.path("reportVersion"));
        assertThat(made.path("title")).isEqualTo(project.path("name"));
        assertThat(made.path("expiresAt").isString()).isTrue(); // 30 days by default

        ApiResponse got = publicShare(client, slug);
        assertThat(got.status()).isEqualTo(200);
        JsonNode body = got.json();
        assertThat(body.path("report").has("traces")).isFalse();
        assertThat(body.has("document")).isFalse(); // the design stays behind; "Open a copy" uses it
        assertThat(body.path("report").path("summary")).isEqualTo(full.path("summary"));
        assertThat(body.path("objectives")).isEqualTo(project.path("document").path("objectives"));
        assertThat(body.path("projectName")).isEqualTo(project.path("name"));
        assertThat(body.path("runLabel").asString()).isEqualTo("Current design");
        assertThat(body.path("canCopy").asBoolean()).isTrue();

        // frozen: changing the design, deleting the run and then the project changes nothing
        client.put("/projects/" + projectId, object("name", "Renamed"));
        assertThat(client.delete("/runs/" + runId).status()).isEqualTo(204);
        assertThat(publicShare(client, slug).json().path("report")).isEqualTo(body.path("report"));
        assertThat(client.delete("/projects/" + projectId).status()).isEqualTo(204);
        JsonNode again = publicShare(client, slug).json();
        assertThat(again.path("report")).isEqualTo(body.path("report"));
        assertThat(again.path("projectName")).isEqualTo(project.path("name"));
        JsonNode listed = client.get("/shares").json(); // still listed, in its workspace
        assertThat(Json.items(listed))
                .extracting(s -> s.path("id").asString() + " " + s.path("projectId") + " " + s.path("runId"))
                .containsExactly(id(made) + " null null");
    }

    @Test
    void telemetryAddressesNeverReachAShare(ApiServer server) {
        ApiClient client = server.client();
        String runId = finishedRun(client, calibratedProject(client));
        // the run's own report names the address: the engine copies the design's calibration
        assertThat(calibrationSource(client.get("/runs/" + runId + "/report").json()))
                .isEqualTo(PROMETHEUS);
        JsonNode made = share(client, runId, object());
        JsonNode body = publicShare(client, slug(made)).json();
        assertThat(calibrationSource(body.path("report"))).isEqualTo("Prometheus (address hidden)");
        Object payload = server.db().value("SELECT payload::text FROM shared_reports WHERE id = ?", id(made));
        assertThat(payload).isNotNull();
        assertThat(String.valueOf(payload)).doesNotContain("hunter2").doesNotContain("prom.internal");
    }

    @Test
    void titleAndExpiryCanBeChosen(ApiClient client) {
        String runId = finishedRun(client, calibratedProject(client));
        JsonNode never = share(client, runId, object("title", "  For the platform review  ", "expiresInDays", null));
        assertThat(never.path("title").asString()).isEqualTo("For the platform review");
        assertThat(never.path("expiresAt").isNull()).isTrue();
        assertThat(publicShare(client, slug(never)).json().path("title").asString())
                .isEqualTo("For the platform review");
        ApiResponse bad = client.post("/runs/" + runId + "/shares", object("expiresInDays", 0));
        assertThat(bad.status()).isEqualTo(422);
    }

    /** The Python test's run stays queued (no worker); here it is a long run, then cancelled. */
    @Test
    void onlyFinishedRunsCanBeShared(ApiClient client) {
        String unfinished = startLongRun(client, calibratedProject(client));
        ApiResponse res = client.post("/runs/" + unfinished + "/shares", object());
        assertThat(res.status()).isEqualTo(409);
        assertThat(res.message()).contains("only finished runs");
        client.post("/runs/" + unfinished + "/cancel");
        Runs.waitFor(client, unfinished);
    }

    // -- 404 once revoked or expired ---------------------------------------------------------------
    @Test
    void revokingAnswers404AtOnceAndDeletesTheCopy(ApiServer server) {
        ApiClient client = server.client();
        String projectId = calibratedProject(client);
        JsonNode made = share(client, finishedRun(client, projectId), object());
        assertThat(publicShare(client, slug(made)).status()).isEqualTo(200);
        assertThat(client.delete("/shares/" + id(made)).status()).isEqualTo(204);
        ApiResponse gone = publicShare(client, slug(made));
        assertThat(gone.status()).isEqualTo(404);
        assertThat(gone.message()).isEqualTo("Share link not found");
        // cleared: SQL NULL, or (as the Python API stores it, through SQLAlchemy's JSON type) a
        // JSON null
        List<Map<String, @Nullable Object>> rows = server.db()
                .rows(
                        "SELECT (payload IS NULL OR jsonb_typeof(payload) = 'null') AS cleared,"
                                + " revoked_at IS NOT NULL AS revoked FROM shared_reports WHERE id = ?",
                        id(made));
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0)).containsEntry("cleared", true).containsEntry("revoked", true);
        assertThat(client.delete("/shares/" + id(made)).status()).isEqualTo(204); // idempotent
        JsonNode listed = client.get("/projects/" + projectId + "/shares").json();
        assertThat(strings(listed, "status")).containsExactly("revoked");
        // the project's history names the link once when made and once when revoked
        List<String> actions = strings(
                client.get("/projects/" + projectId + "/activity").json().path("items"), "action");
        assertThat(actions).containsOnlyOnce("share.create", "share.revoke");
        assertThat(actions.indexOf("share.revoke")).isLessThan(actions.indexOf("share.create")); // newest first
    }

    @Test
    void expiredLinksAnswer404(ApiServer server) {
        ApiClient client = server.client();
        String projectId = calibratedProject(client);
        JsonNode made = share(client, finishedRun(client, projectId), object("expiresInDays", 1));
        server.db().update("UPDATE shared_reports SET expires_at = now() - interval '1 second' WHERE id = ?", id(made));
        assertThat(publicShare(client, slug(made)).status()).isEqualTo(404);
        assertThat(client.get("/projects/" + projectId + "/shares")
                        .json()
                        .path(0)
                        .path("status")
                        .asString())
                .isEqualTo("expired");
    }

    @Test
    void unknownAndMalformedLinksAnswerTheSame404(ApiClient client) {
        for (String slug : List.of("0".repeat(22), "short", "x".repeat(23), "not-base62-but-22-chr!")) {
            ApiResponse res = publicShare(client, slug);
            assertThat(res.status()).as(slug).isEqualTo(404);
            assertThat(res.message()).as(slug).isEqualTo("Share link not found");
        }
    }

    @Test
    void publicAnswersAreNeverCachedOrIndexed(ApiClient client) {
        JsonNode made = share(client, finishedRun(client, calibratedProject(client)), object());
        ApiResponse res = publicShare(client, slug(made));
        assertThat(res.header("cache-control")).isEqualTo("no-store");
        assertThat(res.header("x-robots-tag")).isEqualTo("noindex");
    }

    @Test
    void viewsAreCountedExceptForPreviews(ApiClient client) {
        String projectId = calibratedProject(client);
        JsonNode made = share(client, finishedRun(client, projectId), object());
        for (int i = 0; i < 3; i++) {
            publicShare(client, slug(made));
        }
        client.get(ApiClient.query("/public/shares/" + slug(made), "view", "false")); // a link preview
        JsonNode listed = client.get("/projects/" + projectId + "/shares").json();
        assertThat(listed.path(0).path("viewCount").asInt()).isEqualTo(3);
    }

    // -- roles -------------------------------------------------------------------------------------
    @Test
    void viewersListSharesButOnlyEditorsMakeOrRevokeThem(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String team = Workspaces.enter(owner);
        String projectId = calibratedProject(owner);
        String runId = finishedRun(owner, projectId);
        JsonNode made = share(owner, runId, object());
        ApiClient vera = server.signedIn("vera");
        Workspaces.join(owner, team, vera, "viewer");
        ApiClient ed = server.signedIn("ed");
        Workspaces.join(owner, team, ed, "editor");

        assertThat(vera.get("/projects/" + projectId + "/shares").json().size()).isEqualTo(1);
        assertThat(vera.post("/runs/" + runId + "/shares", object()).status()).isEqualTo(403);
        assertThat(vera.delete("/shares/" + id(made)).status()).isEqualTo(403);

        assertThat(ed.post("/runs/" + runId + "/shares", object()).status()).isEqualTo(201);
        assertThat(ed.delete("/shares/" + id(made)).status()).isEqualTo(204);

        ApiClient edsReadToken = tokenClient(server, ed, "read"); // a read token is a viewer
        assertThat(edsReadToken.post("/runs/" + runId + "/shares", object()).status())
                .isEqualTo(403);
    }

    @Test
    void outsidersLearnNothingAboutAWorkspacesShares(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        String projectId = calibratedProject(owner);
        String runId = finishedRun(owner, projectId);
        JsonNode made = share(owner, runId, object());
        ApiClient olga = server.signedIn("olga"); // no role in the owner's workspace

        assertThat(olga.post("/runs/" + runId + "/shares", object()).status()).isEqualTo(404);
        assertThat(olga.get("/projects/" + projectId + "/shares").status()).isEqualTo(404);
        ApiResponse res = olga.delete("/shares/" + id(made));
        assertThat(res.status()).isEqualTo(404);
        assertThat(res.message()).isEqualTo("Share link not found");
        assertThat(olga.delete("/shares/nope").status()).isEqualTo(404);
        assertThat(publicShare(olga, slug(made)).status()).isEqualTo(200); // the link itself is public
    }

    @Test
    void signedInServersServeSharesToAnyoneWithTheLink(@On(Profile.HOSTED) ApiServer server) {
        ApiClient ada = server.signedIn("ada");
        String ws = Workspaces.first(ada);
        ApiResponse res = ada.post(ApiClient.query("/projects", "workspace", ws), object("document", calibrated()));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        String projectId = id(res.json());
        String runId = finishedRun(ada, projectId); // the run belongs to ada's workspace
        JsonNode made = share(ada, runId, object());

        ApiClient anonymous = server.client();
        assertThat(anonymous.get("/projects/" + projectId + "/shares").status()).isEqualTo(401);
        assertThat(anonymous.post("/runs/" + runId + "/shares", object()).status())
                .isEqualTo(401);
        assertThat(publicShare(anonymous, slug(made)).status()).isEqualTo(200);
        // copying needs a sign-in: the copy lands in your own workspace
        ApiResponse copy = anonymous.post("/projects/from-share", object("slug", slug(made)));
        assertThat(copy.status()).isEqualTo(401);
    }

    // -- the per-address limit ---------------------------------------------------------------------
    /** The rate-limited profile has the hosted limits: 60 public views a minute per address. */
    @Test
    void thePublicPageIsLimitedPerAddress(@On(Profile.RATE_LIMITED) ApiServer server) {
        ApiClient client = server.client();
        String slug = slug(share(client, finishedRun(client, calibratedProject(client)), object()));
        awaitRoomInTheMinute();
        for (int i = 1; i <= PUBLIC_VIEWS_PER_MINUTE; i++) {
            assertThat(publicShare(client, slug).status()).as("view %d", i).isEqualTo(200);
        }
        assertThat(publicShare(client, slug).status()).isEqualTo(429);
        ApiResponse refused = publicShare(client, slug);
        assertThat(refused.status()).isEqualTo(429);
        assertThat(Integer.parseInt(requireNonNull(refused.header("retry-after"))))
                .isGreaterThanOrEqualTo(1);
        assertThat(publicShare(client, "0".repeat(22)).status()).isEqualTo(429); // guessing links counts too
        // another visitor, behind the web server: a client with its own X-ArchSim-Client-IP
        assertThat(publicShare(server.client(), slug).status()).isEqualTo(200);
    }

    @Test
    void withoutLimitsThePublicPageIsNotCounted(ApiClient client) {
        String slug = slug(share(client, finishedRun(client, calibratedProject(client)), object()));
        for (int i = 1; i <= PUBLIC_VIEWS_PER_MINUTE + 1; i++) {
            assertThat(publicShare(client, slug).status()).as("view %d", i).isEqualTo(200);
        }
    }

    // -- open a copy -------------------------------------------------------------------------------
    @Test
    void openACopyMakesAProjectFromTheSharedDesign(@On(Profile.HOSTED) ApiServer server) {
        ApiClient owner = server.signedIn("owner");
        JsonNode project = Runs.createProject(owner, calibrated());
        JsonNode made = share(owner, finishedRun(owner, id(project)), object());
        String team = id(Workspaces.create(owner, "Mine"));
        ApiResponse res =
                owner.post(ApiClient.query("/projects/from-share", "workspace", team), object("slug", slug(made)));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        JsonNode copied = res.json();
        assertThat(copied.path("name").asString())
                .isEqualTo(project.path("name").asString() + " (copy)");
        assertThat(copied.path("document").path("architecture"))
                .isEqualTo(project.path("document").path("architecture"));
        assertThat(Json.write(copied.path("document"))).doesNotContain("hunter2");
        JsonNode inTeam =
                owner.get(ApiClient.query("/projects", "workspace", team)).json();
        assertThat(strings(inTeam, "id")).containsExactly(id(copied));
        ApiResponse named = owner.post("/projects/from-share", object("slug", slug(made), "name", "Fork"));
        assertThat(named.status()).isEqualTo(201);
        assertThat(named.json().path("name").asString()).isEqualTo("Fork");

        ApiClient vera = server.signedIn("vera");
        Workspaces.join(owner, team, vera, "viewer");
        // a viewer cannot add projects to the workspace
        assertThat(vera.post(ApiClient.query("/projects/from-share", "workspace", team), object("slug", slug(made)))
                        .status())
                .isEqualTo(403);
        owner.delete("/shares/" + id(made));
        ApiResponse gone = owner.post("/projects/from-share", object("slug", slug(made)));
        assertThat(gone.status()).isEqualTo(404);
    }

    // -- helpers -----------------------------------------------------------------------------------
    /**
     * A short starter design whose calibration names a Prometheus address with credentials, as
     * the Python tests' stored runs have: the engine copies it into the report's assumptions.
     */
    private static ObjectNode calibrated() {
        ObjectNode doc = Examples.starter(4, 1);
        doc.set("calibration", object("at", "2026-10-01T00:00:00Z", "source", PROMETHEUS, "inputs", 2));
        return doc;
    }

    /** A project of {@link #calibrated()} in the client's workspace; returns its id. */
    private static String calibratedProject(ApiClient client) {
        return id(Runs.createProject(client, calibrated()));
    }

    /** A run of the project's saved design, simulated to the end; returns its id. */
    private static String finishedRun(ApiClient client, String projectId) {
        ApiResponse res = client.post("/projects/" + projectId + "/runs", object());
        assertThat(res.status()).as(res.text()).isEqualTo(202);
        JsonNode done = Runs.waitFor(client, id(res.json()));
        assertThat(done.path("status").asString()).as(done.toString()).isEqualTo("succeeded");
        return id(done);
    }

    /** A run of 120 simulated seconds, still queued or running when this returns; its id. */
    private static String startLongRun(ApiClient client, String projectId) {
        ApiResponse res = client.post("/projects/" + projectId + "/runs", object("document", Examples.starter(120, 5)));
        assertThat(res.status()).as(res.text()).isEqualTo(202);
        return id(res.json());
    }

    private static JsonNode share(ApiClient client, String runId, ObjectNode body) {
        ApiResponse res = client.post("/runs/" + runId + "/shares", body);
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        return res.json();
    }

    private static ApiResponse publicShare(ApiClient client, String slug) {
        return client.get("/public/shares/" + slug);
    }

    /** A client that sends {@code person}'s new API token of {@code scope} (read or write). */
    private static ApiClient tokenClient(ApiServer server, ApiClient person, String scope) {
        ApiResponse res = person.post("/tokens", object("name", "test " + scope, "scope", scope));
        assertThat(res.status()).as(res.text()).isEqualTo(201);
        return server.client()
                .header("Authorization", "Bearer " + res.json().path("token").asString());
    }

    /**
     * Waits for the next minute when less than 20 s of this one are left: the limit counts views
     * per address in windows of a minute, and the views of a test must fall in one.
     */
    private static void awaitRoomInTheMinute() {
        long intoTheMinute = System.currentTimeMillis() % 60_000;
        if (intoTheMinute > 40_000) {
            Runs.pause(Duration.ofMillis(60_000 - intoTheMinute + 250));
        }
    }

    private static String calibrationSource(JsonNode report) {
        return report.path("assumptions").path("calibration").path("source").asString();
    }

    private static String id(JsonNode node) {
        return node.path("id").asString();
    }

    private static String slug(JsonNode share) {
        return share.path("slug").asString();
    }
}
