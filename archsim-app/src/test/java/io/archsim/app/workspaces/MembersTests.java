package io.archsim.app.workspaces;

import java.sql.SQLException;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

import io.archsim.app.access.Person;
import io.archsim.app.access.Principal;
import io.archsim.app.access.WorkspaceRole;
import io.archsim.app.support.HistoryEvent;
import io.archsim.app.support.SameInstant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A workspace's members: listed longest-standing first, owners change roles and remove
 * members, anyone may leave, and a workspace always keeps an owner.
 */
class MembersTests extends WorkspaceTestSupport {

	/** PostgreSQL's SQL state for a lock {@code NOWAIT} could not take. */
	private static final String LOCK_NOT_AVAILABLE = "55P03";

	@Autowired
	private TransactionTemplate transactions;

	@Autowired
	private Members members;

	@Test
	void list_membersWhoJoinedAtTheSameInstant_lowestIdFirst() {
		SignedIn ada = signedIn("Ada");
		String team = newId();
		emptyTeam(team);
		List<String> lowestFirst = SameInstant.storeThree((userId) -> joinAtNine(team, userId));
		member(team, ada.userId(), "owner");

		JsonNode listed = json(send(ada, HttpMethod.GET, WORKSPACES + "/" + team + "/members", null));

		assertThat(listed.findValuesAsString("userId")).containsExactly(lowestFirst.getFirst(), lowestFirst.get(1),
				lowestFirst.getLast(), ada.userId());
	}

	@Test
	void changeRole_ofMember_givesTheNewRole() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		member(team, bob.userId(), "viewer");

		JsonNode changed = json(send(ada, HttpMethod.PATCH, WORKSPACES + "/" + team + "/members/" + bob.userId(),
				"{\"role\": \"editor\"}"));

		assertThat(changed.path("role").asString()).isEqualTo("editor");
	}

	@Test
	void changeRole_ofMember_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		member(team, bob.userId(), "viewer");

		send(ada, HttpMethod.PATCH, WORKSPACES + "/" + team + "/members/" + bob.userId(), "{\"role\": \"editor\"}");

		assertThat(history(team)).last()
			.isEqualTo(new HistoryEvent("member.role", "user", bob.userId(),
					json("{\"from\": \"viewer\", \"to\": \"editor\"}"), null));
	}

	@Test
	void changeRole_lastOwner_isAConflict() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);

		assertThat(send(ada, HttpMethod.PATCH, WORKSPACES + "/" + team + "/members/" + ada.userId(),
				"{\"role\": \"editor\"}"))
			.hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("A workspace needs an owner: make someone else an owner first.");
	}

	@Test
	void changeRole_inATransaction_locksTheWorkspaceUntilItEnds() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		member(team, bob.userId(), "owner");

		this.transactions.executeWithoutResult((status) -> {
			this.members.changeRole(new Person(Principal.session(ada.userId(), "s", null)), team, bob.userId(),
					WorkspaceRole.EDITOR);

			assertLockedForAnotherChange(team);
		});
	}

	@Test
	void remove_inATransaction_locksTheWorkspaceUntilItEnds() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		member(team, bob.userId(), "owner");

		this.transactions.executeWithoutResult((status) -> {
			this.members.remove(new Person(Principal.session(ada.userId(), "s", null)), team, bob.userId());

			assertLockedForAnotherChange(team);
		});
	}

	@Test
	void remove_lastOwner_isAConflict() {
		SignedIn ada = signedIn("Ada");
		String team = team(ada);

		assertThat(send(ada, HttpMethod.DELETE, WORKSPACES + "/" + team + "/members/" + ada.userId(), null))
			.hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("A workspace needs an owner: make someone else an owner first.");
	}

	@Test
	void remove_ownerFromPersonalWorkspace_isAConflict() {
		SignedIn ada = signedIn("Ada");

		assertThat(
				send(ada, HttpMethod.DELETE, WORKSPACES + "/" + ada.workspaceId() + "/members/" + ada.userId(), null))
			.hasStatus(409)
			.bodyJson()
			.extractingPath("$.detail.message")
			.isEqualTo("A personal workspace keeps its owner.");
	}

	@Test
	void remove_member_isRecordedInTheHistory() {
		SignedIn ada = signedIn("Ada");
		SignedIn bob = signedIn("Bob");
		String team = team(ada);
		member(team, bob.userId(), "editor");

		send(ada, HttpMethod.DELETE, WORKSPACES + "/" + team + "/members/" + bob.userId(), null);

		assertThat(history(team)).last()
			.isEqualTo(new HistoryEvent("member.remove", "user", bob.userId(),
					json("{\"role\": \"editor\", \"left\": false}"), null));
	}

	/**
	 * Stores someone new who joined a workspace at nine o'clock.
	 * @param workspaceId the workspace
	 * @param userId their user id
	 */
	private void joinAtNine(String workspaceId, String userId) {
		person(userId, "Member");
		member(workspaceId, userId, "editor", SameInstant.NINE);
	}

	/**
	 * Checks that another change to the workspace's members would have to wait: it takes
	 * the lock {@code FOR NO KEY UPDATE}, which the history's foreign key (a
	 * {@code FOR KEY SHARE} lock) does not block, so only the workspace lock refuses it,
	 * with PostgreSQL's {@code lock_not_available}.
	 * @param workspaceId the workspace
	 */
	private static void assertLockedForAnotherChange(String workspaceId) {
		JdbcClient other = anotherConnection();
		assertThatExceptionOfType(DataAccessException.class)
			.isThrownBy(() -> other.sql("SELECT id FROM workspaces WHERE id = :id FOR NO KEY UPDATE NOWAIT")
				.param("id", workspaceId)
				.query((rows, number) -> rows.getString(1))
				.list())
			.havingRootCause()
			.isInstanceOfSatisfying(SQLException.class,
					(cause) -> assertThat(cause.getSQLState()).isEqualTo(LOCK_NOT_AVAILABLE));
	}

}
