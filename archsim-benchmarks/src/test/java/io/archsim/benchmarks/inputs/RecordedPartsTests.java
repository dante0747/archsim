package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.DatabaseAlone;
import io.archsim.benchmarks.designs.Simulations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each part's inputs from the passes of a kept run without replicates, against what the
 * Python harness derived from them: anchoring moves only the service time and the client
 * latency, so these parts are its own.
 */
class RecordedPartsTests {

	private static final DatabaseInputs DATABASE = new DatabaseInputs(
			new CommitFlush(new DatabaseAlone(Simulations.onTheEngine())));

	// before kafkajs's fetch cycle was reported (W23): the W23 trial's figure
	@Test
	void queue_kafkasSecondRun_isWhatTheHarnessDerived() {
		RecordedRun run = RecordedRun.of("kafka", "2026-10-30T11-20-01Z");

		String inputs = RecordedRun.text(QueueInputs.of(run.passes()));

		assertThat(inputs).isEqualTo(RecordedRun
			.text(run.inputs("publishTimeMs", "consumerTimeMs", "consumerConcurrency", "fetchOverheadMs")));
	}

	// the spread was not anchored, so all but the client latency are the
	// service inputs' own; the service did not report its GC, so it takes its
	// trial's
	@Test
	void service_pgMixedsFifthRun_isWhatTheHarnessDerived() {
		RecordedRun run = RecordedRun.of("pg-mixed", "2026-10-28T23-21-36Z");
		JsonNode calibration = TrialGc.give(run.calibration(),
				TrialGc.of(RepositoryLayout.current().results(), run.experiment()).orElseThrow());

		ObjectNode inputs = ServiceInputs.of(run.experiment(), calibration.get("passes"), CurveForm.FLAT, null);

		inputs.remove("clientLinkLatencyMs");
		assertThat(RecordedRun.text(inputs)).isEqualTo(
				RecordedRun.text(run.inputs("serviceTimeMs", "measuredLowLoad", "gcPauseMs", "gcEveryRequests")));
	}

	// fitted to the whole staircase, the same for every replicate,
	// so the median is each one's
	@Test
	void memory_pgMixedsLatestRun_isWhatTheHarnessDerived() {
		RecordedRun run = RecordedRun.of("pg-mixed", "2026-11-04T11-52-16Z");

		String inputs = RecordedRun.text(MemoryInputs.of(run.experiment(), run.firstReplicate()));

		assertThat(inputs).isEqualTo(RecordedRun.text(RecordedRun
			.ownHows(run.inputs("serviceMemoryBaselineMb", "serviceMemoryPerRpsMb", "serviceMemoryPerRequestMb"))));
	}

	@Test
	void serviceTimeByLoad_pgMixedsLatestRun_isWhatTheHarnessDerived() {
		RecordedRun run = RecordedRun.of("pg-mixed", "2026-11-04T11-52-16Z");

		ObjectNode inputs = ServiceInputs.of(run.experiment(), run.firstReplicate(), CurveForm.FLAT, null);

		assertThat(RecordedRun.text(inputs.get("serviceTimeByLoad")))
			.isEqualTo(RecordedRun.text(RecordedRun.ownHows(run.inputs("serviceTimeByLoad")).get("serviceTimeByLoad")));
	}

	// no idle pass: Redis's CPU per extra command between the two passes
	@Test
	void cache_cacheAsidesSecondRun_isWhatTheHarnessDerived() {
		RecordedRun run = RecordedRun.of("cache-aside", "2026-10-30T10-50-27Z");

		String inputs = RecordedRun.text(CacheInputs.of(run.experiment(), run.passes()));

		assertThat(inputs).isEqualTo(RecordedRun.text(run.inputs("cacheServiceTimeMs", "cacheLinkLatencyMs")));
	}

	// no idle pass and no WAL statistics: the CPU per extra query between the
	// passes, and the flush fitted to the commit wait
	@ParameterizedTest(name = "{0}")
	@CsvSource({ "pg-mixed, 2026-10-28T23-21-36Z", "pg-hot-row, 2026-10-30T10-32-56Z", "kafka, 2026-10-30T11-20-01Z" })
	void database_aRunWithoutReplicates_isWhatTheHarnessDerived(String experiment, String calibratedAt) {
		RecordedRun run = RecordedRun.of(experiment, calibratedAt);

		String inputs = RecordedRun.text(DATABASE.of(run.experiment(), run.passes()));

		assertThat(inputs).isEqualTo(RecordedRun.text(run.inputs("writeServiceTimeMs", "readServiceTimeMs",
				"dbLinkLatencyMs", "dbCpuShare", "commitFlushMs")));
	}

}
