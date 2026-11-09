package io.archsim.app.worker;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * A claimed run's lease, as the Python worker kept it (its {@code test_api_queue.py}):
 * renewed while its worker holds it, with the cancels asked for since; progress and an
 * outcome written only by the worker that holds it, and once; given back when a worker
 * stops without finishing; and reaped when it runs out, requeued once, then failed.
 */
class LeaseRepositoryTests {

	private static final JsonMapper JSON = QueueParts.JSON;

	private static final Instant NOW = QueueFixture.QUEUED.plusSeconds(60);

	private static final Instant LATER = NOW.plusSeconds(5);

	private static final RunOutcome SUCCEEDED = new RunOutcome.Succeeded(JSON.readTree("{\"report\": 1}"),
			JSON.readTree("{\"p50\": 21.0}"), "in", "out", 0.5);

	private final QueueFixture queue = new QueueFixture();

	private final LeaseRepository leases = QueueParts.leases(this.queue);

	private final RunClaims claims = QueueParts.claims(this.queue, "rate-limits=false");

	@Test
	void renew_heldRuns_extendsTheirLeasesAndNamesThoseAskedToStop() {
		List<String> runs = this.queue.queue("ws", 3);
		claim("a", 2);
		claim("b", 1);
		this.queue.requestCancel(runs.get(1), NOW);

		Renewal renewal = this.leases.renew("a", runs, LATER.plusSeconds(30));

		assertThat(renewal).isEqualTo(new Renewal(Set.of(runs.get(0), runs.get(1)), Set.of(runs.get(1))));
		assertThat(this.queue.row(runs.get(0)).leaseExpiresAt()).isEqualTo(LATER.plusSeconds(30));
		assertThat(this.queue.row(runs.get(2)).leaseExpiresAt()).isEqualTo(NOW.plusSeconds(30));
	}

	@Test
	void renew_noRuns_holdsNone() {
		assertThat(this.leases.renew("a", List.of(), LATER)).isEqualTo(new Renewal(Set.of(), Set.of()));
	}

	@Test
	void progress_heldRun_isWritten() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);

		boolean written = this.leases.progress(run, "a", 0.4);

		assertThat(written).isTrue();
		assertThat(this.queue.row(run).progress()).isEqualTo(0.4);
	}

	@Test
	void claim_aRunReapedFromAnotherWorker_isItsSecondAttempt() {
		String run = reapedFromA();

		List<ClaimedRun> taken = claim("b", 1);

		assertThat(taken).extracting(ClaimedRun::id, ClaimedRun::attempts).containsExactly(tuple(run, 2));
	}

	@Test
	void renew_aRunAnotherWorkerTookOver_holdsNone() {
		String run = reapedFromA();
		claim("b", 1);

		Renewal renewal = this.leases.renew("a", List.of(run), LATER);

		assertThat(renewal.owned()).isEmpty();
	}

	@Test
	void progress_aRunAnotherWorkerTookOver_isRefused() {
		String run = reapedFromA();
		claim("b", 1);

		boolean written = this.leases.progress(run, "a", 0.9);

		assertThat(written).isFalse();
		assertThat(this.queue.row(run).progress()).isZero();
	}

	@Test
	void finish_aRunAnotherWorkerTookOver_isRefused() {
		String run = reapedFromA();
		claim("b", 1);

		boolean written = this.leases.finish(run, "a", SUCCEEDED, LATER);

		assertThat(written).isFalse();
		assertThat(this.queue.row(run))
			.isEqualTo(new QueueFixture.RunRow("running", "b", NOW.plusSeconds(30), NOW, null, null, 2, 0.0, null));
	}

	@Test
	void finish_twice_keepsTheFirstOutcome() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);
		this.leases.finish(run, "a", SUCCEEDED, LATER);

		boolean written = this.leases.finish(run, "a", new RunOutcome.Failed(JSON.readTree("{}")), LATER);

		assertThat(written).isFalse();
		assertThat(this.queue.row(run))
			.isEqualTo(new QueueFixture.RunRow("succeeded", "a", null, NOW, LATER, null, 1, 1.0, null));
	}

	@Test
	void finish_succeeded_storesTheReportItsSummaryDigestsAndWallTime() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);

		this.leases.finish(run, "a", SUCCEEDED, LATER);

		assertThat(this.queue.jdbc().sql("""
				SELECT CAST(report AS text) || ' ' || CAST(summary AS text) || ' ' || input_digest || ' '
				    || result_digest || ' ' || wall_seconds
				FROM simulation_runs WHERE id = :id""").param("id", run).query(String.class).single())
			.isEqualTo("{\"report\": 1} {\"p50\": 21.0} in out 0.5");
	}

	@Test
	void finish_failed_storesTheErrorAndLeavesTheProgress() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);
		this.leases.progress(run, "a", 0.4);

		this.leases.finish(run, "a", new RunOutcome.Failed(JSON.readTree("{\"message\": \"boom\"}")), LATER);

		assertThat(this.queue.row(run)).isEqualTo(
				new QueueFixture.RunRow("failed", "a", null, NOW, LATER, null, 1, 0.4, "{\"message\": \"boom\"}"));
	}

	@Test
	void finish_cancelled_leavesTheProgress() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);
		this.leases.progress(run, "a", 0.4);

		this.leases.finish(run, "a", new RunOutcome.Cancelled(), LATER);

		assertThat(this.queue.row(run))
			.isEqualTo(new QueueFixture.RunRow("cancelled", "a", null, NOW, LATER, null, 1, 0.4, null));
	}

	@Test
	void reap_runsWhoseLeaseRanOut_requeuesOnceThenFailsAndHonoursCancels() {
		List<String> runs = this.queue.queue("ws", 5);
		String retry = runs.get(0);
		String giveUp = runs.get(1);
		String cancel = runs.get(2);
		String alive = runs.get(3);
		String noLease = runs.get(4);
		claim("dead", 5);
		this.leases.progress(retry, "dead", 0.4);
		this.queue.attempts(giveUp, 2);
		this.queue.requestCancel(cancel, NOW);
		this.queue.lease(noLease, null);
		List.of(retry, giveUp, cancel).forEach((run) -> this.queue.lease(run, NOW.minusSeconds(1)));

		Reaped reaped = this.leases.reap(NOW);

		assertThat(reaped).isEqualTo(new Reaped(2, 1, 1));
		assertThat(this.queue.row(retry))
			.isEqualTo(new QueueFixture.RunRow("queued", null, null, null, null, null, 1, 0.0, null));
		assertThat(this.queue.row(giveUp).status()).isEqualTo("failed");
		assertThat(JSON.readTree(this.queue.row(giveUp).error()).path("message").stringValue()).isEqualTo(
				"The simulation worker stopped unexpectedly (for example, it ran out of memory or was killed), so "
						+ "this run did not finish. Run it again; if it keeps happening, shorten the run or lower the "
						+ "load.");
		assertThat(this.queue.row(cancel).status()).isEqualTo("cancelled");
		assertThat(this.queue.row(alive).status()).isEqualTo("running");
		assertThat(this.queue.row(noLease).status()).isEqualTo("queued");
	}

	@Test
	void reap_twice_settlesEachRunOnce() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("dead", 1);
		this.queue.lease(run, NOW.minusSeconds(1));
		this.leases.reap(NOW);

		assertThat(this.leases.reap(NOW).total()).isZero();
	}

	@Test
	void release_aRunTheWorkerStopsWithout_requeuesItWithoutCountingTheAttempt() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);
		this.leases.progress(run, "a", 0.4);

		Optional<Released> released = this.leases.release(run, "a", LATER);

		assertThat(released).contains(Released.QUEUED);
		assertThat(this.queue.row(run))
			.isEqualTo(new QueueFixture.RunRow("queued", null, null, null, null, null, 0, 0.0, null));
		assertThat(this.leases.release(run, "a", LATER)).isEmpty();
	}

	@Test
	void release_aRunAskedToStop_cancelsIt() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);
		this.queue.requestCancel(run, NOW);

		Optional<Released> released = this.leases.release(run, "a", LATER);

		assertThat(released).contains(Released.CANCELLED);
		assertThat(this.queue.row(run).status()).isEqualTo("cancelled");
	}

	// worker a took a run, stalled past its lease, and the run was reaped
	private String reapedFromA() {
		String run = this.queue.queue("ws", 1).getFirst();
		claim("a", 1);
		this.leases.progress(run, "a", 0.4);
		this.queue.lease(run, NOW.minusSeconds(1));
		this.leases.reap(NOW);
		return run;
	}

	private List<ClaimedRun> claim(String worker, int limit) {
		return this.claims.claim(new ClaimRequest(worker, limit, Set.of()), NOW);
	}

}
