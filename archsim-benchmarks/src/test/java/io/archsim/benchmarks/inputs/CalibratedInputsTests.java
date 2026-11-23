package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.ProtocolV6Runs;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The last runs under protocol v6 (the held-out campaign and the repeat of 4 Nov 2026)
 * were calibrated by the code this ports, one part at a time and then anchored: their
 * inputs derived again are the very inputs each one used.
 */
class CalibratedInputsTests {

	private static final ModelInputs INPUTS = ModelInputs.asRecorded(Simulations.onTheEngine());

	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed", "python-mixed",
			"chain", "cache-ttl" })
	void rederive_aLastV6Calibration_isTheInputsItUsed(String experiment) {
		JsonNode calibration = calibration(experiment);

		ObjectNode inputs = INPUTS.rederive(Experiments.standard().get(experiment), calibration);

		assertThat(RecordedRun.text(inputs)).isEqualTo(RecordedRun.text(calibration.get("inputs")));
	}

	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed", "python-mixed",
			"chain", "cache-ttl" })
	void recordedCalibration_aLastV6Run_wasCalibratedUnderProtocolV6(String experiment) {
		assertThat(calibration(experiment).get("protocol").stringValue()).isEqualTo("v6");
	}

	// since W22 PostgreSQL's time is its CPU, and the rest of the round trip the link
	// (like the protocol's, a guard on the committed data the parity above relies on)
	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside", "kafka", "java-mixed", "go-mixed", "python-mixed",
			"chain", "cache-ttl" })
	void recordedCalibration_aLastV6Run_hasPostgresqlsLinkAndCpuShare(String experiment) {
		assertThat(calibration(experiment).get("inputs").propertyNames()).contains("dbLinkLatencyMs", "dbCpuShare");
	}

	// since W29 the service time's spread is anchored at the top pass: narrowed from the
	// own time's, its mean still a replicate's CPU per request
	@Test
	void rederive_cacheAsidesLastV6Calibration_anchorsTheServiceTimesSpread() {
		JsonNode calibration = calibration("cache-aside");

		JsonNode serviceTime = INPUTS.rederive(Experiments.standard().get("cache-aside"), calibration)
			.get("serviceTimeMs");

		JsonNode value = serviceTime.get("value");
		double mean = ModelDistributions.mean(value);
		assertThat(value.get("p95").doubleValue() / value.get("median").doubleValue()).isLessThan(7);
		assertThat(serviceTime.get("how").stringValue()).contains("; spread anchored: p95/median ");
		assertThat(calibration.get("inputReplicates")
			.valueStream()
			.mapToDouble(
					(replicate) -> Math.abs(mean / ModelDistributions.mean(replicate.at("/serviceTimeMs/value")) - 1))
			.min()
			.orElseThrow()).isLessThan(0.002);
	}

	// the GC's spacing is the median of the replicates that saw a mark-compact
	@Test
	void rederive_pgMixedsLastV6Calibration_spacesTheGcAsTheReplicatesThatSawIt() {
		JsonNode every = INPUTS.rederive(Experiments.standard().get("pg-mixed"), calibration("pg-mixed"))
			.get("gcEveryRequests");

		assertThat(every.get("value").longValue()).isEqualTo(48_930);
		assertThat(every.get("how").stringValue()).startsWith("the median of the 2 of 3 replicates with it (third)");
	}

	private static JsonNode calibration(String experiment) {
		return ResultFiles.read(ProtocolV6Runs.of(experiment).resolve("calibration.json")).orElseThrow();
	}

}
