package io.archsim.apitests;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Workspaces and roles: what each role may do, 404 for anyone outside a workspace,
 * template copies that stay with whoever made them, invitations. From {@code
 * services/api/tests/test_api_workspaces.py}.
 *
 * <p>
 * The Python tests act as other people in a server without sign-in; here those tests run
 * on the hosted server with real people, who sign in. Each is new and has a personal
 * workspace of their own (their default, where the Python people had {@code default}), so
 * the workspaces they share are team workspaces their owner makes and invites them to.
 *
 * <p>
 * Not ported: {@code test_claim_keeps_each_workspace_under_its_cap}, {@code
 * test_claim_uses_the_kind_cap_for_workspaces_without_one} and {@code
 * test_claim_is_fair_between_workspaces} call the queue's {@code claim} directly, not
 * over HTTP.
 */
@ApiTest(Profile.HOSTED)
class WorkspacesTests {

	// -- without sign-in

	/**
	 * The local server is shared: other tests' team workspaces belong to the local owner
	 * too, so this checks {@code default} among them rather than that it is the only one.
	 */
	@Test
	void me_withoutSignIn_isTheLocalOwnerOfDefault(@On(Profile.LOCAL) ApiServer server) {
		ApiClient client = server.client();
		JsonNode me = client.get("/auth/me").json();
		assertThat(me.path("mode").asString()).isEqualTo("none");
		assertThat(me.path("kind").asString()).isEqualTo("local");
		assertThat(me.path("user").path("id").asString()).isEqualTo("local");
		JsonNode localDefault = Json.find(me.path("workspaces"), "id", "default");
		assertThat(List.of(localDefault.path("role").asString(), localDefault.path("kind").asString()))
			.containsExactly("owner", "team");
		assertThat(client.get("/auth/config").json()).isEqualTo(Json.object("mode", "none", "providers", Json.array()));
		String project = Runs.createProject(client).path("id").asString();
		assertThat(server.db().rows("SELECT workspace_id, created_by FROM projects WHERE id = ?", project))
			.containsExactly(row("workspace_id", "default", "created_by", "local"));
	}

	@Test
	void projects_inTeamWorkspace_areListedAndCreatedThere(@On(Profile.LOCAL) ApiClient client) {
		JsonNode team = Workspaces.create(client, "Platform");
		String teamId = team.path("id").asString();
		assertThat(List.of(team.path("kind").asString(), team.path("role").asString())).containsExactly("team",
				"owner");
		String inDefault = Runs.createProject(client).path("id").asString();
		String inTeam = createProject(client, teamId).path("id").asString();
		// default is shared by every local test: it holds this test's project, not the
		// team's
		assertThat(Json.strings(client.get("/projects").json(), "id")).contains(inDefault).doesNotContain(inTeam);
		JsonNode listed = client.get("/projects", Map.of("X-ArchSim-Workspace", teamId)).json();
		assertThat(Json.strings(listed, "id")).containsExactly(inTeam);
		ApiResponse imported = client.post(ApiClient.query("/projects/import", "workspace", teamId),
				Examples.starter());
		assertThat(imported.status()).as(imported.text()).isEqualTo(201);
		assertThat(client.get(ApiClient.query("/projects", "workspace", "nope")).status()).isEqualTo(404);
	}

	// -- roles

	@Test
	void workspace_asViewer_readsButChangesNothing(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.create(owner, "Team").path("id").asString();
		String project = createProject(owner, team).path("id").asString();
		String run = startRun(owner, project);
		ApiClient vera = server.signedIn("vera");
		Workspaces.join(owner, team, vera, "viewer");
		// the workspace her lists and new projects are in (the Python viewer's default)
		vera.header("X-ArchSim-Workspace", team);

		ApiResponse list = vera.get("/projects");
		assertThat(list.status()).isEqualTo(200);
		assertThat(Json.strings(list.json(), "id")).containsExactly(project);
		assertThat(vera.get("/projects/" + project).status()).isEqualTo(200);
		assertThat(vera.get("/projects/" + project + "/runs").status()).isEqualTo(200);
		assertThat(vera.get("/runs/" + run).status()).isEqualTo(200);
		assertThat(vera.get("/projects/" + project + "/export").status()).isEqualTo(200);
		List<ApiResponse> refused = List.of(vera.put("/projects/" + project, Json.object("name", "Mine now")),
				vera.delete("/projects/" + project), vera.post("/projects/" + project + "/duplicate"),
				vera.post("/projects/" + project + "/runs", Json.object()),
				vera.post("/projects/" + project + "/runs/batch",
						Json.object("scenarioIds", Json.array((Object) null))),
				vera.post("/runs/" + run + "/cancel"), vera.post("/runs/" + run + "/rerun"),
				vera.delete("/runs/" + run), vera.post("/projects", Json.object("exampleId", "cache-aside-starter")),
				vera.post("/projects/import", Examples.starter()));
		for (ApiResponse res : refused) {
			assertThat(res.status()).as(res + ": " + res.text()).isEqualTo(403);
			assertThat(res.message()).as(res.toString()).contains("viewer");
		}
		assertThat(owner.get("/projects/" + project).json().path("name").asString()).isNotEqualTo("Mine now");
	}

	@Test
	void workspace_asEditor_changesProjectsAndRunsButNotTheWorkspace(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.create(owner, "Team").path("id").asString();
		String project = createProject(owner, team).path("id").asString();
		ApiClient ed = server.signedIn("ed");
		Workspaces.join(owner, team, ed, "editor");

		ApiResponse res = ed.put("/projects/" + project, Json.object("name", "Edited"));
		assertThat(res.status()).as(res.text()).isEqualTo(200);
		assertThat(ed.post("/projects/" + project + "/runs", Json.object()).status()).isEqualTo(202);
		assertThat(ed.patch("/workspaces/" + team, Json.object("name", "Mine")).status()).isEqualTo(403);
		assertThat(ed.get("/workspaces/" + team + "/invitations").status()).isEqualTo(403);
		res = ed.patch("/workspaces/" + team + "/members/" + Workspaces.userId(owner), Json.object("role", "viewer"));
		assertThat(res.status()).isEqualTo(403);
	}

	/** The owner's project is in their personal workspace; eve has only her own. */
	@Test
	void workspace_fromOutside_is404ForEverythingInIt(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String ownerWorkspace = Workspaces.first(owner);
		String project = Runs.createProject(owner).path("id").asString();
		String run = startRun(owner, project);
		ApiClient eve = server.signedIn("eve");

		// eve's own (empty) workspace
		assertThat(eve.get("/projects").json()).isEqualTo(Json.array());
		List<ApiResponse> answers = List.of(eve.get("/projects/" + project),
				eve.put("/projects/" + project, Json.object("name", "x")), eve.delete("/projects/" + project),
				eve.get("/projects/" + project + "/export"), eve.get("/projects/" + project + "/runs"),
				eve.post("/projects/" + project + "/runs", Json.object()), eve.get("/runs/" + run),
				eve.get("/runs/" + run + "/input"), eve.get("/runs/" + run + "/report"),
				eve.post("/runs/" + run + "/cancel"), eve.delete("/runs/" + run),
				eve.get(ApiClient.query("/compare", "a", run, "b", run)),
				eve.get(ApiClient.query("/projects", "workspace", ownerWorkspace)),
				eve.get("/workspaces/" + ownerWorkspace), eve.get("/workspaces/" + ownerWorkspace + "/members"));
		for (ApiResponse res : answers) {
			assertThat(res.status()).as(res + ": " + res.text()).isEqualTo(404);
		}
		// the same answer as for something that does not exist
		assertThat(eve.get("/projects/" + project).json()).isEqualTo(Json.object("detail",
				Json.object("message", "Project '" + project + "' not found", "issues", Json.array())));
		assertThat(Json.strings(eve.get("/workspaces").json(), "id")).containsExactly(Workspaces.first(eve));
		assertThat(owner.get("/projects/" + project).status()).isEqualTo(200);
	}

	/** Real tokens: a read token, then a token limited to one workspace. */
	@Test
	void workspace_withReadOnlyToken_actsAsViewer(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String personal = Workspaces.first(owner);
		String project = Runs.createProject(owner).path("id").asString();
		ApiClient readOnly = withToken(server, token(owner, Json.object("name", "read", "scope", "read")));
		assertThat(readOnly.get("/projects/" + project).status()).isEqualTo(200);
		ApiResponse res = readOnly.put("/projects/" + project, Json.object("name", "x"));
		assertThat(res.status()).as(res.text()).isEqualTo(403);
		assertThat(res.message()).contains("viewer");

		String other = Workspaces.create(owner, "Other").path("id").asString();
		String otherProject = createProject(owner, other).path("id").asString();
		ApiClient oneWorkspace = withToken(server, token(owner, Json.object("name", "one", "workspaceId", personal)));
		assertThat(oneWorkspace.get("/projects/" + otherProject).status()).isEqualTo(404);
		assertThat(Json.strings(oneWorkspace.get("/workspaces").json(), "id")).containsExactly(personal);
	}

	// -- template copies (the reuseUnedited leak)

	@Test
	void openTemplate_byAnotherMember_neverHandsBackSomeoneElsesCopy(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.create(owner, "Team").path("id").asString();
		Opened mine = openTemplate(owner, team);
		assertThat(mine.status()).isEqualTo(201);
		// my unedited copy comes back
		assertThat(openTemplate(owner, team)).isEqualTo(new Opened(200, mine.id()));
		ApiClient ann = server.signedIn("ann");
		Workspaces.join(owner, team, ann, "editor");
		Opened hers = openTemplate(ann, team);
		assertThat(hers.status()).isEqualTo(201);
		// not mine, though it is in her workspace too
		assertThat(hers.id()).isNotEqualTo(mine.id());
		assertThat(openTemplate(ann, team)).isEqualTo(new Opened(200, hers.id()));
		String second = Workspaces.create(owner, "Second").path("id").asString();
		Opened inSecond = openTemplate(owner, second);
		assertThat(inSecond.status()).isEqualTo(201);
		assertThat(inSecond.id()).isNotIn(mine.id(), hers.id());
		assertThat(openTemplate(owner, team)).isEqualTo(new Opened(200, mine.id()));
	}

	private static Opened openTemplate(ApiClient client, String workspace) {
		ApiResponse res = client.post(ApiClient.query("/projects", "workspace", workspace),
				Json.object("exampleId", "cache-aside-starter", "reuseUnedited", true));
		assertThat(res.status()).as(res.text()).isIn(200, 201);
		return new Opened(res.status(), res.json().path("id").asString());
	}

	// -- members and invitations

	@Test
	void acceptInvitation_once_makesAMember(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.create(owner, "Team").path("id").asString();
		JsonNode created = owner.post("/workspaces/" + team + "/invitations", Json.object("role", "editor")).json();
		String token = created.path("token").asString();
		String invitation = created.path("id").asString();
		assertThat(token).startsWith("asim_inv_");
		JsonNode pending = owner.get("/workspaces/" + team + "/invitations").json();
		assertThat(Json.strings(pending, "id")).containsExactly(invitation);
		assertThat(pending.get(0).has("token")).isFalse();
		Object hash = server.db().value("SELECT token_hash FROM invitations WHERE id = ?", invitation);
		assertThat(hash).isInstanceOf(String.class);
		assertThat((String) hash).isNotEqualTo(token).doesNotContain(token);

		ApiClient joe = server.signedIn("joe");
		JsonNode preview = joe.post("/invitations/preview", Json.object("token", token)).json();
		assertThat(List.of(preview.path("workspaceName").asString(), preview.path("role").asString()))
			.containsExactly("Team", "editor");
		JsonNode joined = joe.post("/invitations/accept", Json.object("token", token)).json();
		assertThat(List.of(joined.path("id").asString(), joined.path("role").asString())).containsExactly(team,
				"editor");
		ApiResponse again = joe.post("/invitations/accept", Json.object("token", token));
		assertThat(again.status()).isEqualTo(404); // used up
		JsonNode members = owner.get("/workspaces/" + team + "/members").json();
		assertThat(Json.items(members))
			.extracting((member) -> member.path("userId").asString() + " " + member.path("role").asString())
			.containsExactlyInAnyOrder(Workspaces.userId(owner) + " owner", Workspaces.userId(joe) + " editor");
	}

	/**
	 * Kim signs in with GitHub, so she has an identity from the start; the Python test
	 * adds one. Its email is changed in the database instead, as the Python test does:
	 * none, then the invited address unverified, then verified.
	 */
	@Test
	void acceptInvitation_forAnEmail_needsThatEmailVerified(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.create(owner, "Team").path("id").asString();
		FakeGitHub.Account account = server.newAccount("kim");
		String email = Objects.requireNonNull(account.email());
		String invited = "K" + email.substring(1).replace("@example.com", "@Example.com");
		String token = owner
			.post("/workspaces/" + team + "/invitations", Json.object("role", "viewer", "email", invited))
			.json()
			.path("token")
			.asString();
		ApiClient kim = server.client();
		assertThat(server.signIn(kim, account.login())).doesNotStartWith("/signin");
		String kimId = Workspaces.userId(kim);
		Db db = server.db();
		db.update("UPDATE identities SET email = NULL, email_verified = false WHERE user_id = ?", kimId);
		assertThat(kim.post("/invitations/accept", Json.object("token", token)).status()).isEqualTo(403);
		db.update("UPDATE identities SET email = ? WHERE user_id = ?", email, kimId);
		// not verified yet
		assertThat(kim.post("/invitations/accept", Json.object("token", token)).status()).isEqualTo(403);
		db.update("UPDATE identities SET email_verified = true WHERE user_id = ?", kimId);
		assertThat(kim.post("/invitations/accept", Json.object("token", token)).status()).isEqualTo(200);
	}

	@Test
	void acceptInvitation_expiredOrRevoked_isNotFound(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.create(owner, "Team").path("id").asString();
		String url = "/workspaces/" + team + "/invitations";
		JsonNode expired = owner.post(url, Json.object()).json();
		JsonNode revoked = owner.post(url, Json.object()).json();
		server.db()
			.update("UPDATE invitations SET expires_at = now() - interval '1 second' WHERE id = ?",
					expired.path("id").asString());
		assertThat(owner.delete(url + "/" + revoked.path("id").asString()).status()).isEqualTo(204);
		ApiClient late = server.signedIn("late");
		for (JsonNode invitation : List.of(expired, revoked)) {
			ApiResponse res = late.post("/invitations/accept", Json.object("token", invitation.path("token")));
			assertThat(res.status()).as(res.text()).isEqualTo(404);
		}
		assertThat(owner.get(url).json()).isEqualTo(Json.array());
	}

	/**
	 * A signed-in person's own personal workspace stands in for the one the Python test
	 * writes to the database: giving the local owner a personal workspace would make it
	 * their default for every other local test. {@code default} is the local owner's.
	 */
	@Test
	void shareOrDelete_personalWorkspace_isConflict(ApiServer server, @On(Profile.LOCAL) ApiClient local) {
		ApiClient owner = server.signedIn("owner");
		String mine = Workspaces.first(owner);
		assertThat(owner.get("/workspaces/" + mine).json().path("kind").asString()).isEqualTo("personal");
		ApiResponse res = owner.post("/workspaces/" + mine + "/invitations", Json.object());
		assertThat(res.status()).as(res.text()).isEqualTo(409);
		assertThat(owner.delete("/workspaces/" + mine).status()).isEqualTo(409);
		assertThat(local.delete("/workspaces/default").status()).isEqualTo(409);
	}

	@Test
	void removeOwner_whenLastOwner_isConflict(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String ownerId = Workspaces.userId(owner);
		String team = Workspaces.create(owner, "Team").path("id").asString();
		String members = "/workspaces/" + team + "/members";
		assertThat(owner.patch(members + "/" + ownerId, Json.object("role", "editor")).status()).isEqualTo(409);
		assertThat(owner.delete(members + "/" + ownerId).status()).isEqualTo(409);
		ApiClient coOwner = server.signedIn("co");
		String coOwnerId = Workspaces.userId(coOwner);
		Workspaces.setRole(server.db(), team, coOwnerId, "editor");
		assertThat(owner.patch(members + "/" + coOwnerId, Json.object("role", "owner")).status()).isEqualTo(200);
		// leaving: co is an owner
		assertThat(owner.delete(members + "/" + ownerId).status()).isEqualTo(204);
		assertThat(coOwner.delete("/workspaces/" + team).status()).isEqualTo(204);
	}

	@Test
	void removeMember_themselves_leavesTheWorkspace(ApiServer server) {
		ApiClient owner = server.signedIn("owner");
		String team = Workspaces.create(owner, "Team").path("id").asString();
		ApiClient pat = server.signedIn("pat");
		String patId = Workspaces.userId(pat);
		Workspaces.setRole(server.db(), team, patId, "viewer");
		String members = "/workspaces/" + team + "/members";
		assertThat(pat.delete(members + "/" + Workspaces.userId(owner)).status()).isEqualTo(403);
		assertThat(pat.delete(members + "/" + patId).status()).isEqualTo(204);
		assertThat(pat.get("/workspaces/" + team).status()).isEqualTo(404);
	}

	/** The run may be queued or already running when its workspace goes. */
	@Test
	void deleteWorkspace_withProjectsAndRuns_deletesThem(@On(Profile.LOCAL) ApiServer server) {
		ApiClient client = server.client();
		String team = Workspaces.create(client, "Team").path("id").asString();
		String project = createProject(client, team).path("id").asString();
		String run = startRun(client, project);
		assertThat(client.delete("/workspaces/" + team).status()).isEqualTo(204);
		Db db = server.db();
		assertThat(db.value("SELECT count(*) FROM projects WHERE id = ?", project)).isEqualTo(0L);
		assertThat(db.value("SELECT count(*) FROM simulation_runs WHERE id = ?", run)).isEqualTo(0L);
	}

	@Test
	void run_withoutSignIn_stillSimulatesInDefault(@On(Profile.LOCAL) ApiServer server) {
		ApiClient client = server.client();
		String project = Runs.createProject(client).path("id").asString();
		String run = startRun(client, project);
		assertThat(Runs.waitFor(client, run).path("status").asString()).isEqualTo("succeeded");
		assertThat(server.db().rows("SELECT workspace_id, created_by FROM simulation_runs WHERE id = ?", run))
			.containsExactly(row("workspace_id", "default", "created_by", "local"));
	}

	// -- helpers

	/** A project of the starter design in {@code workspace}. */
	private static JsonNode createProject(ApiClient client, String workspace) {
		ApiResponse res = client.post(ApiClient.query("/projects", "workspace", workspace),
				Json.object("document", Examples.starter()));
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		return res.json();
	}

	/** Starts a run of the project's saved document; returns its id. */
	private static String startRun(ApiClient client, String project) {
		ApiResponse res = client.post("/projects/" + project + "/runs", Json.object());
		assertThat(res.status()).as(res.text()).isEqualTo(202);
		return res.json().path("id").asString();
	}

	/** A personal API token of {@code person}'s (a signed-in session makes it). */
	private static String token(ApiClient person, JsonNode body) {
		ApiResponse res = person.post("/tokens", body);
		assertThat(res.status()).as(res.text()).isEqualTo(201);
		return res.json().path("token").asString();
	}

	/** A client that sends {@code token} and nothing else: no cookies, no CSRF header. */
	private static ApiClient withToken(ApiServer server, String token) {
		return server.client().header("Authorization", "Bearer " + token);
	}

	private static Map<String, Object> row(String firstColumn, Object firstValue, String secondColumn,
			Object secondValue) {
		return Map.of(firstColumn, firstValue, secondColumn, secondValue);
	}

	/** What opening a template answered: its status and the project it handed back. */
	private record Opened(int status, String id) {
	}

}
