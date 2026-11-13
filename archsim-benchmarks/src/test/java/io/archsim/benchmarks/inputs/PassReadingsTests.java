package io.archsim.benchmarks.inputs;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What a calibration pass read, and what it must have read for today's derivation.
 */
class PassReadingsTests {

	@Test
	void timing_aPassWithoutTheTiming_isRefusedNamingIt() {
		ObjectNode stats = JsonNodeFactory.instance.objectNode();
		stats.putObject("timings").putObject("own").put("count", 1);

		assertThatExceptionOfType(MissingReadingException.class).isThrownBy(() -> PassReadings.timing(stats, "db_read"))
			.withMessage("calibration recorded no 'db_read' timings");
	}

	@Test
	void pass_aCalibrationWithoutThePass_isRefusedNamingIt() {
		ObjectNode passes = JsonNodeFactory.instance.objectNode();
		passes.putObject("high");

		assertThatExceptionOfType(MissingReadingException.class)
			.isThrownBy(() -> PassReadings.pass(passes, "write_async:1"))
			.withMessage("calibration recorded no 'write_async:1' pass");
	}

}
