package io.archsim.app.worker;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.archsim.engine.simulator.SimulationMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Claims on the run queue, as the Python worker made them (its
 * {@code test_api_queue.py}): the oldest runs first, under a lease, one more attempt
 * counted; never one run to two workers, however many claim at once; never a run the
 * worker is still executing; each workspace under its cap of runs at once, counted across
 * workers; and every workspace's oldest run before anyone's second.
 */
class RunClaimsTests {

	private static final Instant NOW = QueueFixture.QUEUED.plusSeconds(60);

	/** The advisory lock the Python worker's claims took turns on, 0x6172636873696D02. */
	private static final long PYTHON_CLAIM_LOCK = 7021784069288062210L;

	private final QueueFixture queue = new QueueFixture();

	private final RunClaims claims = claims("rate-limits=false");

	@Test
	void claim_threeQueuedRuns_takesTheTwoOldestUnderALease() {
		List<String> runs = this.queue.queue("ws", 3);

		List<ClaimedRun> claimed = this.claims.claim(new ClaimRequest("a", 2, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::id).containsExactlyInAnyOrder(runs.get(0), runs.get(1));
		assertThat(claimed).extracting(ClaimedRun::attempts).containsOnly(1);
		assertThat(this.queue.row(runs.get(0)))
			.isEqualTo(new QueueFixture.RunRow("running", "a", NOW.plusSeconds(30), NOW, null, null, 1, 0.0, null));
	}

	@Test
	void claim_afterAnotherWorkerTookTheOldest_takesTheRest() {
		List<String> runs = this.queue.queue("ws", 3);
		this.claims.claim(new ClaimRequest("a", 2, Set.of()), NOW);

		List<ClaimedRun> claimed = this.claims.claim(new ClaimRequest("b", 5, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::id).containsExactly(runs.get(2));
	}

	@Test
	void claim_nothingQueued_takesNothing() {
		this.queue.queue("ws", 1);
		this.claims.claim(new ClaimRequest("a", 1, Set.of()), NOW);

		assertThat(this.claims.claim(new ClaimRequest("b", 5, Set.of()), NOW)).isEmpty();
	}

	@Test
	void claim_noFreeSlot_takesNothing() {
		this.queue.queue("ws", 1);

		assertThat(this.claims.claim(new ClaimRequest("a", 0, Set.of()), NOW)).isEmpty();
	}

	@Test
	void claim_aRun_hasWhatItsSimulationNeeds() {
		String run = this.queue.queue("ws", 2).get(1);
		this.claims.claim(new ClaimRequest("a", 1, Set.of()), NOW);

		ClaimedRun claimed = this.claims.claim(new ClaimRequest("a", 1, Set.of()), NOW).getFirst();

		assertThat(claimed).isEqualTo(new ClaimedRun(run, QueueParts.JSON.readTree("{\"n\": 1}"), null, 1, 1000L, 20.0,
				1, SimulationMode.FULL));
	}

	@Test
	void claim_fourWorkersAtOnce_handOutEachRunOnce() throws InterruptedException, ExecutionException {
		List<String> runs = this.queue.queue("ws", 24);
		List<String> taken = new ArrayList<>();

		try (ExecutorService workers = Executors.newFixedThreadPool(4)) {
			List<Future<List<String>>> claimers = IntStream.range(0, 4)
				.mapToObj((index) -> workers.submit(() -> claimUntilEmpty("w" + index)))
				.toList();
			for (Future<List<String>> claimer : claimers) {
				taken.addAll(claimer.get());
			}
		}

		assertThat(taken).containsExactlyInAnyOrderElementsOf(runs);
	}

	@Test
	void claim_whileAnotherClaimHasTheTurn_waitsUntilItEnds()
			throws SQLException, InterruptedException, ExecutionException, TimeoutException {
		this.queue.queue("ws", 1);

		try (Connection other = this.queue.database().getConnection();
				Statement turn = other.createStatement();
				ExecutorService worker = Executors.newSingleThreadExecutor()) {
			other.setAutoCommit(false);
			turn.execute("SELECT pg_advisory_xact_lock(" + PYTHON_CLAIM_LOCK + ")");
			Future<List<ClaimedRun>> claim = worker
				.submit(() -> this.claims.claim(new ClaimRequest("a", 1, Set.of()), NOW));

			await().during(Duration.ofMillis(300)).atMost(Duration.ofSeconds(2)).until(() -> !claim.isDone());
			other.commit();
			assertThat(claim.get(10, TimeUnit.SECONDS)).hasSize(1);
		}
	}

	@Test
	void claim_aQuickCheck_isClaimedAsOne() {
		String run = this.queue.queue("ws", 1).getFirst();
		this.queue.sampled(run);

		List<ClaimedRun> claimed = this.claims.claim(new ClaimRequest("a", 1, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::mode).containsExactly(SimulationMode.SAMPLED);
	}

	@Test
	void claim_aRunTheWorkerIsStillExecuting_skipsIt() {
		String run = this.queue.queue("ws", 1).getFirst();

		assertThat(this.claims.claim(new ClaimRequest("a", 1, Set.of(run)), NOW)).isEmpty();
		assertThat(this.claims.claim(new ClaimRequest("a", 1, Set.of()), NOW)).extracting(ClaimedRun::id)
			.containsExactly(run);
	}

	@Test
	void claim_aWorkspaceWithItsOwnCap_staysUnderItOnEveryWorker() {
		List<String> capped = this.queue.queue("capped", 3);
		this.queue.cap("capped", 1);
		List<String> free = this.queue.queue("free", 2);

		List<ClaimedRun> first = this.claims.claim(new ClaimRequest("w1", 4, Set.of()), NOW);
		List<ClaimedRun> second = this.claims.claim(new ClaimRequest("w2", 4, Set.of()), NOW);

		assertThat(first).extracting(ClaimedRun::id).containsExactlyInAnyOrder(capped.get(0), free.get(0), free.get(1));
		assertThat(second).isEmpty();
	}

	@Test
	void claim_aCappedWorkspaceWhoseRunEnded_takesItsNext() {
		List<String> capped = this.queue.queue("capped", 3);
		this.queue.cap("capped", 1);
		this.claims.claim(new ClaimRequest("w1", 4, Set.of()), NOW);
		QueueParts.leases(this.queue).finish(capped.get(0), "w1", new RunOutcome.Cancelled(), NOW);

		List<ClaimedRun> claimed = this.claims.claim(new ClaimRequest("w2", 4, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::id).containsExactly(capped.get(1));
	}

	@Test
	void claim_eightWorkersAtOnce_keepAWorkspaceUnderItsCap() throws InterruptedException, ExecutionException {
		this.queue.queue("capped", 10);
		this.queue.cap("capped", 2);
		List<String> taken = new ArrayList<>();

		try (ExecutorService workers = Executors.newFixedThreadPool(8)) {
			List<Future<List<ClaimedRun>>> claimers = IntStream.range(0, 8)
				.mapToObj((index) -> workers
					.submit(() -> this.claims.claim(new ClaimRequest("w" + index, 2, Set.of()), NOW)))
				.toList();
			for (Future<List<ClaimedRun>> claimer : claimers) {
				claimer.get().forEach((run) -> taken.add(run.id()));
			}
		}

		assertThat(taken).hasSize(2);
	}

	@Test
	void claim_aSandbox_keepsToItsKindsCap() {
		List<String> sandbox = this.queue.queue("sand", "sandbox", 3);

		List<ClaimedRun> claimed = this.claims.claim(new ClaimRequest("w", 5, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::id).containsExactly(sandbox.get(0));
	}

	@Test
	void claim_aTeamWithTheHostedLimits_keepsToItsKindsCap() {
		List<String> team = this.queue.queue("team", 6);

		List<ClaimedRun> claimed = claims("rate-limits=true").claim(new ClaimRequest("w", 6, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::id).containsExactlyInAnyOrderElementsOf(team.subList(0, 4));
	}

	@Test
	void claim_aKindTheAppDoesNotKnowWithTheHostedLimits_getsThePersonalCap() {
		List<String> unknown = this.queue.queue("other", "enterprise", 3);

		List<ClaimedRun> claimed = claims("rate-limits=true").claim(new ClaimRequest("w", 3, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::id).containsExactlyInAnyOrderElementsOf(unknown.subList(0, 2));
	}

	@Test
	void claim_aBusyWorkspaceQueuedFirst_sharesWithOneQueuedLater() {
		List<String> busy = this.queue.queue("busy", 5);
		List<String> late = this.queue.queue("late", 1);

		List<ClaimedRun> claimed = this.claims.claim(new ClaimRequest("w", 2, Set.of()), NOW);

		assertThat(claimed).extracting(ClaimedRun::id).containsExactlyInAnyOrder(busy.get(0), late.get(0));
	}

	private List<String> claimUntilEmpty(String worker) {
		List<String> taken = new ArrayList<>();
		List<ClaimedRun> batch = this.claims.claim(new ClaimRequest(worker, 2, Set.of()), NOW);
		while (!batch.isEmpty()) {
			batch.forEach((run) -> taken.add(run.id()));
			batch = this.claims.claim(new ClaimRequest(worker, 2, Set.of()), NOW);
		}
		return taken;
	}

	private RunClaims claims(String settings) {
		return QueueParts.claims(this.queue, settings);
	}

}
