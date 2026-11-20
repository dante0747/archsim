package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which curve a derivation gives the service's CPU per request: today's the law of its
 * rate (ADR-0093), the one the kept runs were recorded with the flat curve. Cache-aside's
 * latest calibration, whose cost falls with load.
 */
class CurveDerivationTests {

	private static final Experiment CACHE_ASIDE = Experiments.standard().get("cache-aside");

	private static final JsonNode CALIBRATION = ResultFiles
		.read(RepositoryLayout.current().results().resolve("cache-aside").resolve("calibration.json"))
		.orElseThrow();

	@Test
	void rederive_todaysDerivation_fitsTheServicesCostAsALawOfItsRate() {
		JsonNode curve = new ModelInputs(Simulations.onTheEngine()).rederive(CACHE_ASIDE, CALIBRATION)
			.get("serviceTimeByLoad");

		assertThat(curve.get("how").stringValue()).contains("fitted as a law of the rate");
	}

	@Test
	void rederive_asRecorded_keepsTheCurveFlatAboveTheStaircase() {
		JsonNode curve = ModelInputs.asRecorded(Simulations.onTheEngine())
			.rederive(CACHE_ASIDE, CALIBRATION)
			.get("serviceTimeByLoad");

		assertThat(curve.get("how").stringValue()).contains("fitted never rising")
			.endsWith("flat outside the staircase");
	}

}
