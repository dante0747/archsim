package io.archsim.app.worker;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.run.SimulationLimitException;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.engine.simulator.RunRequest;
import io.archsim.engine.simulator.SimulationMode;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a claimed run's simulation ends, written as the Python worker wrote it: a report
 * with the summary the run lists show, its digests and time; an invalid design's first
 * error and its issues; a limit's words; anything else as an internal error, named by its
 * type. A run simulates with its seed, its mode and the tighter of the worker's limits
 * and its own.
 */
@Timeout(value = 30, threadMode = ThreadMode.SEPARATE_THREAD)
class ExecutionTests {

	/** The summary of {@link StandIns#REPORT}. */
	private static final JsonNode SUMMARY = QueueParts.JSON.readTree("""
			{"offeredRps": 100.0, "throughputRps": 99.5, "successRate": 0.99, "timeoutRate": 0.0,
			 "errorRate": 0.01, "p50": 21.0, "p95": 30.5, "p99": 41.0, "bottleneck": "api is saturated",
			 "bottleneckNodeId": "api"}""");

	private final QueueFixture queue = new QueueFixture();

	private final ExecutionFixture executions = new ExecutionFixture(this.queue);

	@AfterEach
	void stopTheThreads() {
		this.executions.close();
	}

	@Test
	void start_aRunThatSimulates_writesItsReportSummaryDigestsAndTime() {
		ClaimedRun run = this.executions.claimed();

		this.executions.execution(run, StandIns.reporting()).start().join();

		assertThat(this.queue.row(run.id()).status()).isEqualTo("succeeded");
		assertThat(this.queue.row(run.id()).progress()).isEqualTo(1.0);
		assertThat(QueueParts.JSON.readTree(this.queue.text(run.id(), "summary"))).isEqualTo(SUMMARY);
		assertThat(List.of(this.queue.text(run.id(), "input_digest"), this.queue.text(run.id(), "result_digest"),
				this.queue.text(run.id(), "wall_seconds")))
			.containsExactly("in-digest", "out-digest", "0.25");
	}

	@Test
	void start_anInvalidDesign_failsWithItsFirstErrorAndItsIssues() {
		ClaimedRun run = this.executions.claimed();
		Issue missing = Issue.error("edge.target_missing", "Link 'a' references node 'b', which does not exist.")
			.atEdge("a");

		this.executions.execution(run, StandIns.throwing(new DocumentInvalidException(List.of(missing))))
			.start()
			.join();

		assertThat(QueueParts.JSON.readTree(this.queue.row(run.id()).error())).isEqualTo(QueueParts.JSON.readTree("""
				{"message": "Link 'a' references node 'b', which does not exist.",
				 "issues": [{"severity": "error", "code": "edge.target_missing",
				             "message": "Link 'a' references node 'b', which does not exist.",
				             "nodeId": null, "edgeId": "a", "path": null, "hint": null}]}"""));
	}

	@Test
	void start_aRunOverALimit_failsWithTheLimitsWords() {
		ClaimedRun run = this.executions.claimed();

		this.executions.execution(run, StandIns.throwing(new SimulationLimitException("Too many requests.")))
			.start()
			.join();

		assertThat(this.queue.row(run.id()).error()).isEqualTo("{\"message\": \"Too many requests.\"}");
	}

	@Test
	void start_aFaultInTheSimulator_failsAsAnInternalErrorNamedByItsType() {
		ClaimedRun run = this.executions.claimed();

		this.executions.execution(run, StandIns.throwing(new IllegalStateException("a fault"))).start().join();

		assertThat(this.queue.row(run.id()).error())
			.isEqualTo("{\"message\": \"Internal error while simulating: IllegalStateException: a fault\"}");
	}

	@Test
	void start_aReportWithoutItsProvenance_failsAsAnInternalError() {
		ClaimedRun run = this.executions.claimed();
		RunSimulator incomplete = (document, request, observer) -> QueueParts.JSON.readTree("{\"summary\": {}}");

		this.executions.execution(run, incomplete).start().join();

		assertThat(this.queue.row(run.id()).status()).isEqualTo("failed");
		assertThat(QueueParts.JSON.readTree(this.queue.row(run.id()).error()).path("message").stringValue())
			.startsWith("Internal error while simulating: JsonNodeException: ");
	}

	@Test
	void start_aRun_simulatesWithItsSeedModeAndTheTighterLimits() {
		ClaimedRun run = this.executions.claimed();
		AtomicReference<RunRequest> asked = new AtomicReference<>();

		this.executions.execution(run, (document, request, observer) -> {
			asked.set(request);
			return StandIns.REPORT;
		}).start().join();

		assertThat(asked.get()).isEqualTo(RunRequest.of(null)
			.withSeed(run.seed())
			.withMode(SimulationMode.FULL)
			.withLimits(new SimulationLimits(1000, 20.0)));
	}

}
