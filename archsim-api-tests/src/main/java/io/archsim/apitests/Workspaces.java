package io.archsim.apitests;

import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Workspaces for tests. A server is shared by every test of its profile, and without
 * sign-in they are all the same person in the same default workspace; a test that lists
 * or counts what it made works in a team workspace of its own, which starts empty.
 */
public final class Workspaces {

	private Workspaces() {
	}

	/**
	 * A new team workspace (its creator owns it); fails the test unless it answers 201.
	 */
	public static JsonNode create(ApiClient client, String name) {
		ApiResponse res = client.post("/workspaces", Json.object("name", name));
		if (res.status() != 201) {
			throw new AssertionError(res + ": " + res.text());
		}
		return res.json();
	}

	/**
	 * A new team workspace that {@code client} works in from now on: it is sent as {@code
	 * X-ArchSim-Workspace} with every request. Returns its id.
	 */
	public static String enter(ApiClient client) {
		String id = create(client, "Test").path("id").asString();
		client.header("X-ArchSim-Workspace", id);
		return id;
	}

	/** The signed-in person's user id ({@code GET /auth/me}). */
	public static String userId(ApiClient client) {
		return me(client).path("user").path("id").asString();
	}

	/**
	 * The first workspace {@code GET /auth/me} lists: a new person's personal workspace.
	 */
	public static String first(ApiClient client) {
		return me(client).path("workspaces").path(0).path("id").asString();
	}

	private static JsonNode me(ApiClient client) {
		ApiResponse res = client.get("/auth/me");
		if (res.status() != 200) {
			throw new AssertionError(res + ": " + res.text());
		}
		return res.json();
	}

	/**
	 * {@code member} joins {@code workspaceId} as {@code role} the way people do:
	 * {@code owner} makes an invitation and {@code member} accepts it. Returns the accept
	 * answer.
	 */
	public static JsonNode join(ApiClient owner, String workspaceId, ApiClient member, String role) {
		ApiResponse invitation = owner.post("/workspaces/" + workspaceId + "/invitations", Json.object("role", role));
		if (invitation.status() != 201) {
			throw new AssertionError(invitation + ": " + invitation.text());
		}
		ApiResponse accepted = member.post("/invitations/accept",
				Json.object("token", invitation.json().path("token")));
		if (accepted.status() != 200) {
			throw new AssertionError(accepted + ": " + accepted.text());
		}
		return accepted.json();
	}

	/**
	 * A workspace written straight to the database, owned by {@code ownerUserId}: the way
	 * to get a sandbox (or a second personal workspace), which no route makes. Returns
	 * its id.
	 */
	public static String insert(Db db, String kind, String ownerUserId) {
		String id = "t" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
		db.update("INSERT INTO workspaces (id, name, kind, created_at) VALUES (?, ?, ?, now())", id, "Test " + kind,
				kind);
		setRole(db, id, ownerUserId, "owner");
		return id;
	}

	/** Gives {@code userId} {@code role} in a workspace, straight in the database. */
	public static void setRole(Db db, String workspaceId, String userId, String role) {
		db.update(
				"INSERT INTO memberships (workspace_id, user_id, role, created_at) VALUES (?, ?, ?, now())"
						+ " ON CONFLICT (workspace_id, user_id) DO UPDATE SET role = EXCLUDED.role",
				workspaceId, userId, role);
	}

}
