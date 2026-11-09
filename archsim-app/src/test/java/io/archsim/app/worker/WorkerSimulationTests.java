package io.archsim.app.worker;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.Timeout.ThreadMode;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.simulator.Simulator;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the worker simulates on the Java simulator: a whole run, a quick check answered
 * from its sample, and runs of one design with their seeds, each run's seed giving its
 * own result.
 */
@Timeout(value = 120, threadMode = ThreadMode.SEPARATE_THREAD)
class WorkerSimulationTests {

	private static final Duration A_WHILE = Duration.ofSeconds(90);

	/**
	 * Clients calling an API that answers in 20 ms, at a steady load; a quick check
	 * samples it.
	 */
	private static final String DESIGN = """
			{"schemaVersion": "1.7", "kind": "archsim.architecture", "metadata": {"name": "case"},
			 "architecture": {"autoSize": false,
			   "nodes": [{"id": "clients", "type": "traffic_source", "config": {}},
			             {"id": "api", "type": "external_api",
			              "config": {"latencyMs": {"kind": "exponential", "mean": 20}}}],
			   "edges": [{"id": "clients-api", "source": "clients", "target": "api"}]},
			 "workload": %s}""";

	private static final String SHORT = """
			{"seed": 7, "warmupS": 1, "durationS": 6, "rps": 100}""";

	private static final String LONG = """
			{"seed": 7, "warmupS": 2, "durationS": 600, "rps": 2000}""";

	private final QueueFixture queue = new QueueFixture();

	private final RunSimulator simulator = new Simulator(StandardComponents.registry(), PriceSnapshots.shipped())::run;

	@Test
	void start_aWholeRun_writesItsReport() {
		String run = this.queue.queueDesign(DESIGN.formatted(SHORT), 7, "full");

		simulate(1);

		assertThat(this.queue.row(run).status()).isEqualTo("succeeded");
		assertThat(report(run).has("simulationMode")).isFalse();
		assertThat(report(run).path("summary").path("throughputRps").doubleValue()).isPositive();
	}

	@Test
	void start_aQuickCheck_isAnsweredFromItsSample() {
		String run = this.queue.queueDesign(DESIGN.formatted(LONG), 7, "sampled");

		simulate(1);

		assertThat(report(run).path("simulationMode").path("kind").stringValue()).isEqualTo("sampled");
	}

	@Test
	void start_runsOfOneDesign_simulateEachWithItsSeed() {
		List<String> runs = List.of(this.queue.queueDesign(DESIGN.formatted(SHORT), 7, "full"),
				this.queue.queueDesign(DESIGN.formatted(SHORT), 7, "full"),
				this.queue.queueDesign(DESIGN.formatted(SHORT), 8, "full"));

		simulate(3);

		List<String> digests = runs.stream().map((run) -> this.queue.text(run, "result_digest")).toList();
		assertThat(digests.get(0)).isEqualTo(digests.get(1)).isNotEqualTo(digests.get(2));
	}

	// a worker with these slots simulates every run queued
	private void simulate(int slots) {
		try (WorkerFixture worker = new WorkerFixture(this.queue, this.simulator, slots)) {
			worker.worker().start();
			await().atMost(A_WHILE)
				.until(() -> this.queue.jdbc()
					.sql("SELECT count(*) FROM simulation_runs WHERE status IN ('queued', 'running')")
					.query(Integer.class)
					.single() == 0);
		}
	}

	private JsonNode report(String run) {
		return QueueParts.JSON.readTree(this.queue.text(run, "report"));
	}

}
