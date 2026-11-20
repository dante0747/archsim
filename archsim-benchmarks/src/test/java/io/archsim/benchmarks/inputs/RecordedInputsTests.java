package io.archsim.benchmarks.inputs;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.archsim.benchmarks.designs.Simulations;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A kept run's inputs derived again from its calibration and anchored again, against what
 * the Python harness derived with the Java engine before it was 1.0.0
 * ({@code results/dev/0.16.0-java/}): one run of each stack, and one without replicates.
 */
class RecordedInputsTests {

	private static final ModelInputs INPUTS = ModelInputs.asRecorded(Simulations.onTheEngine());

	@ParameterizedTest(name = "{0} {1}")
	@CsvSource({ "pg-mixed, 2026-11-04T11-52-16Z", "pg-hot-row, 2026-11-04T12-24-30Z", "kafka, 2026-10-30T11-20-01Z" })
	void rederive_aKeptRun_isWhatTheHarnessDerived(String experiment, String calibratedAt) {
		RecordedRun run = RecordedRun.of(experiment, calibratedAt);

		String inputs = RecordedRun.text(INPUTS.rederive(run.experiment(), run.calibrationAsChecked()));

		assertThat(inputs).isEqualTo(RecordedRun.text(run.check().get("inputs")));
	}

}
