package io.archsim.benchmarks;

import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The setup's derivation of the model inputs: the designs get a host machine only when
 * {@code BENCH_HOST=on} asks for one. pg-mixed's latest calibration derived again.
 */
class HarnessSetupTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final JsonNode CALIBRATION = ResultFiles.read(LAYOUT.results().resolve("pg-mixed/calibration.json"))
		.orElseThrow();

	@Test
	void modelInputs_hostSwitchedOn_givesTheDesignsTheMachine() {
		HarnessSetup setup = Setups.onThisMachine(LAYOUT, new Logs().log(), Map.of("BENCH_HOST", "on", "BENCH_HOST_FIT",
				LAYOUT.results().resolve("trials/2026-11-01-w27/what-if-fit.json").toString()));

		JsonNode inputs = setup.modelInputs(Simulations.onTheEngine())
			.rederive(Experiments.standard().get("pg-mixed"), CALIBRATION);

		assertThat(inputs.at("/host/value/physicalCores").intValue()).isEqualTo(4);
	}

	@Test
	void machine_hostSwitchedOnWithoutAFit_saysTheDesignsHaveNone(@TempDir Path folder) {
		Logs logs = new Logs();
		HarnessSetup setup = Setups.onThisMachine(LAYOUT, logs.log(),
				Map.of("BENCH_HOST", "on", "BENCH_HOST_FIT", folder.resolve("missing.json").toString()));

		assertThat(setup.machine()).isEmpty();
		assertThat(logs.text().lines())
			.containsExactly("[12:34:56] BENCH_HOST=on, but no machine to put the designs on "
					+ "(no quiet contention check, or no fit where BENCH_HOST_FIT points): the designs have none");
	}

	@Test
	void machine_hostNotSwitchedOn_saysNothing() {
		Logs logs = new Logs();

		assertThat(Setups.onThisMachine(LAYOUT, logs.log()).machine()).isEmpty();
		assertThat(logs.text()).isEmpty();
	}

	@Test
	void modelInputs_hostNotSwitchedOn_givesTheDesignsNone() {
		HarnessSetup setup = Setups.onThisMachine(LAYOUT, new Logs().log());

		JsonNode inputs = setup.modelInputs(Simulations.onTheEngine())
			.rederive(Experiments.standard().get("pg-mixed"), CALIBRATION);

		assertThat(inputs.has("host")).isFalse();
	}

}
