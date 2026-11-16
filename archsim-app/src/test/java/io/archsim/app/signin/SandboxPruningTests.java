package io.archsim.app.signin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.archsim.app.ArchsimApplication;
import io.archsim.app.Role;
import io.archsim.app.database.TestDatabases;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

/**
 * Sandboxes go once they expire: the guest, their sessions, the sandbox and everything in
 * it, its runs included, which a worker simulating one then stops. The API prunes them;
 * no other role does.
 */
class SandboxPruningTests extends SandboxTestSupport {

	/** Longer than a delete takes, shorter than one that waits for a lock. */
	private static final Duration A_MOMENT = Duration.ofSeconds(10);

	@Autowired
	private Guests guests;

	@Test
	void prune_expiredSandbox_deletesTheGuestAndTheirSessions() {
		SignedIn guest = guest();
		expire(guest);

		new SandboxPruning(this.guests).prune();

		assertThat(count("users", "id", guest.userId())).isZero();
		assertThat(count("sessions", "user_id", guest.userId())).isZero();
	}

	@Test
	void prune_expiredSandbox_deletesTheWorkspaceWithItsProjectsAndRuns() {
		SignedIn guest = guest();
		String project = created(send(guest, HttpMethod.POST, "/api/v1/projects", "{}")).path("id").asString();
		run(guest, project);
		expire(guest);

		new SandboxPruning(this.guests).prune();

		assertThat(count("workspaces", "id", guest.workspaceId())).isZero();
		assertThat(count("projects", "id", project)).isZero();
		assertThat(count("simulation_runs", "project_id", project)).isZero();
	}

	@Test
	void prune_sandboxBeforeItExpires_keepsIt() {
		SignedIn guest = guest();

		new SandboxPruning(this.guests).prune();

		assertThat(count("workspaces", "id", guest.workspaceId())).isOne();
		assertThat(send(guest, HttpMethod.GET, "/api/v1/auth/me", null)).hasStatusOk();
	}

	@Test
	void prune_someoneSignedIn_isKept() {
		SignedIn ada = signedIn("Ada");

		new SandboxPruning(this.guests).prune();

		assertThat(count("users", "id", ada.userId())).isOne();
	}

	@Test
	void delete_personWithASandbox_keepsIt() {
		SignedIn ada = signedIn("Ada");
		String sandbox = newId();
		jdbc()
			.sql("INSERT INTO workspaces (id, name, kind, created_by, created_at) "
					+ "VALUES (:id, 'Sandbox', 'sandbox', :user, now())")
			.param("id", sandbox)
			.param("user", ada.userId())
			.update();

		boolean deleted = this.guests.delete(ada.userId());

		assertThat(deleted).isFalse();
		assertThat(count("workspaces", "id", sandbox)).isOne();
		assertThat(count("users", "id", ada.userId())).isOne();
	}

	@Test
	void delete_guestBeforeTheSandboxExpires_keepsThem() {
		SignedIn guest = guest();

		boolean deleted = this.guests.delete(guest.userId());

		assertThat(deleted).isFalse();
		assertThat(count("workspaces", "id", guest.workspaceId())).isOne();
		assertThat(count("users", "id", guest.userId())).isOne();
	}

	@Test
	void delete_expiredGuest_saysItDeletedThem() {
		SignedIn guest = guest();
		expire(guest);

		boolean deleted = this.guests.delete(guest.userId());

		assertThat(deleted).isTrue();
		assertThat(count("users", "id", guest.userId())).isZero();
	}

	@Test
	void delete_guestAnotherInstanceIsDeleting_leavesThemWithoutWaiting() {
		SignedIn guest = guest();
		expire(guest);

		Boolean deleted = whileAnotherTransactionLocks(guest.userId(), () -> this.guests.delete(guest.userId()));

		assertThat(deleted).isFalse();
		assertThat(count("users", "id", guest.userId())).isOne();
		assertThat(count("workspaces", "id", guest.workspaceId())).isOne();
	}

	@Test
	void prune_aDatabaseThatFails_isNotThrown() {
		Guests failing = mock();
		given(failing.expired()).willThrow(new CannotGetJdbcConnectionException("down"));

		assertThatNoException().isThrownBy(new SandboxPruning(failing)::prune);
	}

	@Test
	void prune_oneGuestTheDatabaseFailsToDelete_deletesTheOthers() {
		Guests oneStuck = mock();
		given(oneStuck.expired()).willReturn(List.of("stuck", "next"));
		given(oneStuck.delete("stuck")).willThrow(new QueryTimeoutException("locked"));

		new SandboxPruning(oneStuck).prune();

		then(oneStuck).should().delete("next");
	}

	@Test
	void start_theApiRole_prunesSandboxes() {
		try (ConfigurableApplicationContext api = start(Role.API)) {
			assertThat(api.getBeansOfType(SandboxPruning.class)).hasSize(1);
		}
	}

	@Test
	void start_theWorkerRole_hasNoPruner() {
		try (ConfigurableApplicationContext worker = start(Role.WORKER)) {
			assertThat(worker.getBeansOfType(SandboxPruning.class)).isEmpty();
		}
	}

	/**
	 * Runs a delete while another transaction, as another instance's prune, holds the
	 * guest's row; fails the test if the delete waits for it.
	 */
	private static @Nullable Boolean whileAnotherTransactionLocks(String userId, Supplier<Boolean> delete) {
		DataSource other = anotherDataSource();
		return new TransactionTemplate(new JdbcTransactionManager(other)).execute((status) -> {
			JdbcClient.create(other)
				.sql("SELECT id FROM users WHERE id = :id FOR UPDATE")
				.param("id", userId)
				.query((rows, number) -> rows.getString("id"))
				.single();
			CompletableFuture<Boolean> deleting = CompletableFuture.supplyAsync(delete);
			assertThat(deleting).succeedsWithin(A_MOMENT);
			return deleting.join();
		});
	}

	private void expire(SignedIn guest) {
		jdbc().sql("UPDATE users SET expires_at = now() - interval '1 minute' WHERE id = :id")
			.param("id", guest.userId())
			.update();
	}

	/** A run waiting in the queue, as starting one leaves it. */
	private void run(SignedIn guest, String projectId) {
		jdbc().sql("""
				INSERT INTO simulation_runs (id, project_id, status, progress, seed, engine_version, created_at,
				                             input_document, workspace_id, created_by)
				VALUES (:id, :project, 'queued', 0, 1, 'test', now(), '{}', :workspace, :user)""")
			.param("id", newId())
			.param("project", projectId)
			.param("workspace", guest.workspaceId())
			.param("user", guest.userId())
			.update();
	}

	private int count(String table, String column, String value) {
		return jdbc().sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :value")
			.param("value", value)
			.query(Integer.class)
			.single();
	}

	private static ConfigurableApplicationContext start(Role role) {
		return new SpringApplicationBuilder(ArchsimApplication.class).web(role.webApplicationType())
			.profiles(role.profile())
			.run("--archsim.database-url=" + TestDatabases.migratedJdbcUrl(), "--server.port=0");
	}

}
