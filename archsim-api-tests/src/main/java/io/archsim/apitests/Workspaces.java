package io.archsim.apitests;

import java.net.HttpURLConnection;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

/**
 * Workspaces for tests. A server is shared by every test of its profile, and without
 * sign-in they are all the same person in the same default workspace; a test that lists
 * or counts what it made works in a team workspace of its own, which starts empty.
 */
public final class Workspaces {

	/** How many random hex digits follow the {@code t} of an inserted workspace's id. */
	private static final int INSERTED_ID_DIGITS = 20;

	private Workspaces() {
	}

	/**
	 * Creates a team workspace (its creator owns it); fails the test unless it answers
	 * 201.
	 * @param client the client that creates it
	 * @param name the workspace's name
	 * @return the new workspace
	 */
	public static JsonNode create(ApiClient client, String name) {
		return client.post("/workspaces", Json.object("name", name))
			.expectStatus(HttpURLConnection.HTTP_CREATED)
			.json();
	}

	/**
	 * Creates a team workspace that {@code client} works in from now on: it is sent as
	 * {@code X-ArchSim-Workspace} with every request.
	 * @param client the client that creates the workspace and works in it
	 * @return the workspace's id
	 */
	public static String enter(ApiClient client) {
		String id = Json.id(create(client, "Test"));
		client.header("X-ArchSim-Workspace", id);
		return id;
	}

	/**
	 * Returns the signed-in person's user id ({@code GET /auth/me}).
	 * @param client the person's client
	 * @return the person's user id
	 */
	public static String userId(ApiClient client) {
		return Json.id(me(client).path("user"));
	}

	/**
	 * Returns the first workspace {@code GET /auth/me} lists.
	 * @param client the person's client
	 * @return the workspace's id: a new person's personal workspace
	 */
	public static String first(ApiClient client) {
		return Json.id(me(client).path("workspaces").path(0));
	}

	/**
	 * Makes {@code member} join a workspace the way people do: {@code owner} makes an
	 * invitation and {@code member} accepts it.
	 * @param owner a client that may invite to the workspace
	 * @param workspaceId the workspace to join
	 * @param member the client that joins
	 * @param role the role {@code member} gets
	 * @return the answer to accepting the invitation
	 */
	public static JsonNode join(ApiClient owner, String workspaceId, ApiClient member, String role) {
		JsonNode invitation = owner.post("/workspaces/" + workspaceId + "/invitations", Json.object("role", role))
			.expectStatus(HttpURLConnection.HTTP_CREATED)
			.json();
		return member.post("/invitations/accept", Json.object("token", invitation.path("token")))
			.expectStatus(HttpURLConnection.HTTP_OK)
			.json();
	}

	/**
	 * Writes a workspace straight to the database: the way to get a sandbox (or a second
	 * personal workspace), which no route makes.
	 * @param db the server's database
	 * @param kind the workspace's kind, e.g. {@code sandbox}
	 * @param ownerUserId the user who owns the workspace
	 * @return the workspace's id
	 */
	public static String insert(Db db, String kind, String ownerUserId) {
		String id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, INSERTED_ID_DIGITS);
		db.update("INSERT INTO workspaces (id, name, kind, created_at) VALUES (?, ?, ?, now())", id, "Test " + kind,
				kind);
		setRole(db, id, ownerUserId, "owner");
		return id;
	}

	/**
	 * Gives a user a role in a workspace, straight in the database.
	 * @param db the server's database
	 * @param workspaceId the workspace
	 * @param userId the user, a member already or not
	 * @param role the user's new role
	 */
	public static void setRole(Db db, String workspaceId, String userId, String role) {
		db.update(
				"INSERT INTO memberships (workspace_id, user_id, role, created_at) VALUES (?, ?, ?, now())"
						+ " ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = EXCLUDED.role",
				workspaceId, userId, role);
	}

	private static JsonNode me(ApiClient client) {
		return client.get("/auth/me").expectStatus(HttpURLConnection.HTTP_OK).json();
	}

}
