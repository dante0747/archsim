package io.archsim.apitests;

import java.util.Arrays;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An API token, read or write, changes no workspace, membership, role or invitation:
 * managing access takes the person signed in to the web app, and a token is refused with
 * {@code 403} before anything changes, even for a change its owner could make in person.
 */
@ApiTest(Profile.HOSTED)
class TokenWorkspaceChangesTests {

	private static final String TOKENS_CANNOT = "API tokens cannot do this: sign in to the web app instead.";

	static Stream<Arguments> changesAndScopes() {
		return Arrays.stream(WorkspaceChange.values())
			.flatMap((change) -> Stream.of(Arguments.of(change, "read"), Arguments.of(change, "write")));
	}

	@ParameterizedTest(name = "{0} with a {1} token")
	@MethodSource("changesAndScopes")
	void change_withAToken_isForbiddenAndChangesNothing(WorkspaceChange change, String scope, ApiServer server) {
		ApiClient ada = server.signedIn("ada");
		Team team = team(server, ada);
		JsonNode before = state(ada, team);
		ApiClient token = server.tokenClient(ada, scope);

		ApiResponse response = change.send(token, team);

		assertThat(response.status()).as(response.text()).isEqualTo(403);
		assertThat(response.message()).isEqualTo(TOKENS_CANNOT);
		assertThat(state(ada, team)).isEqualTo(before);
	}

	/**
	 * A team workspace Ada owns with Bob, a second owner, so that each change is one Ada
	 * could make in person, and an invitation still open.
	 */
	private static Team team(ApiServer server, ApiClient ada) {
		String id = Json.id(Workspaces.create(ada, "Team"));
		ApiClient bob = server.signedIn("bob");
		Workspaces.join(ada, id, bob, "owner");
		JsonNode invitation = ada.post("/workspaces/" + id + "/invitations", Json.object("role", "viewer"))
			.expectStatus(201)
			.json();
		return new Team(id, Workspaces.userId(ada), Workspaces.userId(bob), Json.id(invitation),
				invitation.path("token").asString());
	}

	/**
	 * What a change could touch: the team, its members, its invitations, Ada's
	 * workspaces.
	 */
	private static JsonNode state(ApiClient ada, Team team) {
		ObjectNode state = Json.object();
		state.set("team", ada.get("/workspaces/" + team.id()).expectStatus(200).json());
		state.set("members", ada.get("/workspaces/" + team.id() + "/members").expectStatus(200).json());
		state.set("invitations", ada.get("/workspaces/" + team.id() + "/invitations").expectStatus(200).json());
		state.set("workspaces", ada.get("/workspaces").expectStatus(200).json());
		return state;
	}

	/**
	 * A team workspace and who and what is in it.
	 *
	 * @param id the workspace
	 * @param holderId the token's owner, Ada, an owner of the team
	 * @param otherOwnerId the team's other owner, Bob
	 * @param invitationId an invitation still open
	 * @param invitationToken that invitation's token
	 */
	record Team(String id, String holderId, String otherOwnerId, String invitationId, String invitationToken) {

	}

	/**
	 * Every route that changes a workspace, its members, their roles or its invitations.
	 */
	enum WorkspaceChange {

		/** Renames the team. */
		RENAME,

		/** Makes Bob an editor. */
		DEMOTE,

		/** Removes Bob. */
		REMOVE,

		/** Takes Ada, the token's owner, out of the team. */
		LEAVE,

		/** Invites someone. */
		INVITE,

		/** Revokes the open invitation. */
		REVOKE,

		/** Accepts the open invitation. */
		ACCEPT,

		/** Deletes the team. */
		DELETE,

		/** Makes another team workspace. */
		CREATE;

		/** Sends the change's request with the client's token. */
		ApiResponse send(ApiClient client, Team team) {
			String workspace = "/workspaces/" + team.id();
			return switch (this) {
				case RENAME -> client.patch(workspace, Json.object("name", "Renamed"));
				case DEMOTE ->
					client.patch(workspace + "/members/" + team.otherOwnerId(), Json.object("role", "editor"));
				case REMOVE -> client.delete(workspace + "/members/" + team.otherOwnerId());
				case LEAVE -> client.delete(workspace + "/members/" + team.holderId());
				case INVITE -> client.post(workspace + "/invitations", Json.object("role", "viewer"));
				case REVOKE -> client.delete(workspace + "/invitations/" + team.invitationId());
				case ACCEPT -> client.post("/invitations/accept", Json.object("token", team.invitationToken()));
				case DELETE -> client.delete(workspace);
				case CREATE -> client.post("/workspaces", Json.object("name", "Another"));
			};
		}

	}

}
