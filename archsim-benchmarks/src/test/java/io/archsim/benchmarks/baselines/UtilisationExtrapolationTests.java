package io.archsim.benchmarks.baselines;

import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The knee by utilisation extrapolation, on a hand-made staircase of three passes of 30
 * s: the service burns 10 ms of CPU a second at rest and 0.05 cores and 2 ms a request
 * above that, PostgreSQL 20 ms a second at rest and 1 ms a request.
 */
class UtilisationExtrapolationTests {

	private static final ContainerCpus ONE_AND_TWO = new ContainerCpus(Map.of("api", 1, "postgres", 2), Set.of(),
			"environment.json");

	private static final String STAIRCASE = """
			{"idle": {"containerCpuMsPerSecond": {"api": 10.0, "postgres": 20.0}},
			 "staircase": [
			  {"rateRps": 100, "containerCpuMs": {"api": 7800.0, "postgres": 3600.0}},
			  {"rateRps": 200, "containerCpuMs": {"api": 13800.0, "postgres": 6600.0}},
			  {"rateRps": 300, "containerCpuMs": {"api": 19800.0, "postgres": 9600.0}}]}
			""";

	/** Two passes in which the service's CPU is the same at both rates. */
	private static final String FLAT_SERVICE = """
			{"idle": {"containerCpuMsPerSecond": {"api": 10.0, "postgres": 20.0}},
			 "staircase": [
			  {"rateRps": 100, "containerCpuMs": {"api": 7800.0, "postgres": 3600.0}},
			  {"rateRps": 200, "containerCpuMs": {"api": 7800.0, "postgres": 6600.0}}]}
			""";

	// 0.05 + 0.002 cores a request a second reaches 1 CPU less 0.01 at rest at 470 req/s
	@Test
	void of_aStaircase_extrapolatesEachContainersLineToItsCpusLessItsCpuAtRest() {
		JsonNode calibration = JsonText.read(STAIRCASE);

		ObjectNode extrapolation = UtilisationExtrapolation.of(calibration, ONE_AND_TWO);

		assertThat(extrapolation.get("containers").get(0)).isEqualTo(JsonText.read("""
				{"container": "api", "cpus": 1, "onRequestPath": true, "passes": 3, "idleCores": 0.01,
				 "interceptCores": 0.05, "cpuMsPerRequest": 2.0, "rSquared": 1.0, "capacityRps": 470.0}
				"""));
	}

	@Test
	void of_aStaircase_takesTheLowestLoadAsTheKnee() {
		JsonNode calibration = JsonText.read(STAIRCASE);

		ObjectNode extrapolation = UtilisationExtrapolation.of(calibration, ONE_AND_TWO);

		assertThat(extrapolation.get("kneeRps").doubleValue()).isEqualTo(470.0);
		assertThat(extrapolation.get("limitedBy").stringValue()).isEqualTo("api");
	}

	@Test
	void of_aStaircase_recordsItsRatesAndWhereTheCpusCameFrom() {
		JsonNode calibration = JsonText.read(STAIRCASE);

		ObjectNode extrapolation = UtilisationExtrapolation.of(calibration, ONE_AND_TWO);

		assertThat(extrapolation.get("ratesRps")).isEqualTo(JsonText.read("[100, 200, 300]"));
		assertThat(extrapolation.get("cpusFrom").stringValue()).isEqualTo("environment.json");
	}

	// PostgreSQL's 0.001 cores a request a second reaches 2 CPUs less 0.02 at 1,980 req/s
	@Test
	void of_aContainerBehindAQueue_doesNotBoundTheKnee() {
		ContainerCpus serviceBehind = new ContainerCpus(Map.of("api", 1, "postgres", 2), Set.of("api"),
				"environment.json");

		ObjectNode extrapolation = UtilisationExtrapolation.of(JsonText.read(STAIRCASE), serviceBehind);

		assertThat(extrapolation.get("kneeRps").doubleValue()).isEqualTo(1980.0);
		assertThat(extrapolation.get("containers").get(0).get("onRequestPath").booleanValue()).isFalse();
	}

	@Test
	void of_aCalibrationWithoutAStaircase_hasNoKneeAndSaysWhy() {
		JsonNode twoPasses = JsonText.read("{\"passes\": {\"low\": {}, \"high\": {}}}");

		ObjectNode extrapolation = UtilisationExtrapolation.of(twoPasses, ONE_AND_TWO);

		assertThat(PythonValues.isNone(extrapolation.get("kneeRps"))).isTrue();
		assertThat(extrapolation.get("why").stringValue()).startsWith("the calibration has no staircase");
	}

	@Test
	void of_aContainerWhoseCpuDoesNotRiseWithTheRate_hasNoCapacityAndSaysWhy() {
		JsonNode flatService = JsonText.read(FLAT_SERVICE);

		ObjectNode extrapolation = UtilisationExtrapolation.of(flatService, ONE_AND_TWO);

		assertThat(PythonValues.isNone(extrapolation.get("containers").get(0).get("capacityRps"))).isTrue();
		assertThat(extrapolation.get("containers").get(0).get("why").stringValue())
			.isEqualTo("its CPU did not rise with the rate");
		assertThat(extrapolation.get("kneeRps").doubleValue()).isEqualTo(1980.0);
	}

	// the service's CPU is flat, and PostgreSQL works behind a queue
	@Test
	void of_noContainerOnTheRequestPathWithARisingLine_hasNoKneeAndSaysWhy() {
		JsonNode flatService = JsonText.read(FLAT_SERVICE);
		ContainerCpus databaseBehind = new ContainerCpus(Map.of("api", 1, "postgres", 2), Set.of("postgres"),
				"environment.json");

		ObjectNode extrapolation = UtilisationExtrapolation.of(flatService, databaseBehind);

		assertThat(PythonValues.isNone(extrapolation.get("kneeRps"))).isTrue();
		assertThat(PythonValues.isNone(extrapolation.get("limitedBy"))).isTrue();
		assertThat(extrapolation.get("why").stringValue())
			.isEqualTo("no container a request waits for has a line that rises with the rate");
	}

	// a line is extrapolated to its container's CPUs: without them there is no knee to
	// give
	@Test
	void of_aContainerWithoutKnownCpus_isRefused() {
		JsonNode calibration = JsonText.read(STAIRCASE);
		ContainerCpus serviceOnly = new ContainerCpus(Map.of("api", 1), Set.of(), "environment.json");

		assertThatIllegalStateException().isThrownBy(() -> UtilisationExtrapolation.of(calibration, serviceOnly))
			.withMessage("no CPUs known for the container postgres");
	}

	// the idle pass is among the passes since protocol v5
	@Test
	void of_anIdlePassAmongThePasses_netsItsCpuAtRest() {
		JsonNode calibration = JsonText.read("""
				{"passes": {"idle": {"containerCpuMsPerSecond": {"api": 10.0}}},
				 "staircase": [
				  {"rateRps": 100, "containerCpuMs": {"api": 7800.0}},
				  {"rateRps": 300, "containerCpuMs": {"api": 19800.0}}]}
				""");

		ObjectNode extrapolation = UtilisationExtrapolation.of(calibration, ONE_AND_TWO);

		assertThat(extrapolation.get("kneeRps").doubleValue()).isEqualTo(470.0);
	}

}
