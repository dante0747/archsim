package io.archsim.app.runs;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A project keeps its newest runs, the ones its list shows, and the older runs it still
 * needs: those a worker has yet to finish, and those a review refers to. The others go,
 * oldest first, a batch at a time, while other instances prune too.
 */
class RunRetentionTests extends RunRetentionTestSupport {

	/** Longer than a prune takes, shorter than one that waits for a lock. */
	private static final Duration A_MOMENT = Duration.ofSeconds(10);

	@Test
	void prune_projectPastItsLimit_keepsItsNewestRuns() {
		String project = project();
		String oldest = run(project, 5);
		String older = run(project, 4);
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOf(project)).hasSize(KEPT).doesNotContain(oldest, older);
	}

	@Test
	void prune_projectWithinItsLimit_keepsEveryRun() {
		String project = project();
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOf(project)).hasSize(KEPT);
	}

	@ParameterizedTest
	@ValueSource(strings = { "queued", "running" })
	void prune_runAWorkerHasYetToFinishPastTheLimit_isKept(String status) {
		String project = project();
		String active = run(project, 5, status);
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOf(project)).contains(active);
	}

	@Test
	void prune_signedOffRunPastTheLimit_isKept() {
		String project = project();
		String signedOff = run(project, 5);
		signOff(signedOff);
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOf(project)).contains(signedOff);
	}

	@Test
	void prune_commentedRunPastTheLimit_isKept() {
		String project = project();
		String commented = run(project, 5);
		comment(commented);
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOf(project)).contains(commented);
	}

	@Test
	void prune_runWithAShareLink_keepsTheLinksCopy() {
		String project = project();
		String shared = run(project, 5);
		jdbc().sql("""
				INSERT INTO shared_reports (id, slug, workspace_id, project_id, run_id, title, payload, payload_version,
				                            report_version, created_at)
				VALUES (:id, :id, 'default', :project, :run, 'Baseline', '{"report": {}}', 1, '1.4', now())""")
			.param("id", newId())
			.param("project", project)
			.param("run", shared)
			.update();
		newestRuns(project);

		retention().prune(NOW);

		assertThat(runsOf(project)).doesNotContain(shared);
		assertThat(jdbc().sql("SELECT payload::text FROM shared_reports WHERE project_id = :project")
			.param("project", project)
			.query((rows, number) -> rows.getString(1))
			.single()).isEqualTo("{\"report\": {}}");
	}

	@Test
	void prune_morePastTheLimitThanABatch_deletesThemAll() {
		String project = project();
		jdbc().sql("""
				INSERT INTO simulation_runs (id, project_id, workspace_id, status, progress, seed, engine_version,
				                             input_document, created_at)
				SELECT md5(:project || n), :project, 'default', 'succeeded', 1, 1, 'test', '{}',
				       now() - make_interval(mins => 10 + n)
				FROM generate_series(1, 250) AS n""").param("project", project).update();
		newestRuns(project);

		int pruned = retention().prune(NOW);

		assertThat(pruned).isEqualTo(250);
		assertThat(runsOf(project)).hasSize(KEPT);
	}

	@Test
	void prune_runAnotherInstanceIsPruning_isLeftToItWithoutWaiting() {
		String project = project();
		String held = run(project, 6);
		String free = run(project, 5);
		newestRuns(project);

		whileAnotherTransactionHolds(held, () -> retention().prune(NOW));

		assertThat(runsOf(project)).contains(held).doesNotContain(free);
	}

	/**
	 * Prunes while another transaction, as another instance's prune, holds a run; fails
	 * the test if the prune waits for it.
	 * @param runId the run held
	 * @param prune the prune
	 */
	private void whileAnotherTransactionHolds(String runId, IntSupplier prune) {
		DataSource other = anotherDataSource();
		new TransactionTemplate(new JdbcTransactionManager(other)).executeWithoutResult((status) -> {
			JdbcClient.create(other)
				.sql("SELECT id FROM simulation_runs WHERE id = :id FOR UPDATE")
				.param("id", runId)
				.query((rows, number) -> rows.getString("id"))
				.single();
			assertThat(CompletableFuture.supplyAsync(prune::getAsInt)).succeedsWithin(A_MOMENT);
		});
	}

}
