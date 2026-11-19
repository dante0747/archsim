package io.archsim.benchmarks.inputs;

import java.util.OptionalDouble;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * A container's busy cores in a calibration pass of 30 s, net of what it burns at rest.
 */
class CpuByLoadTests {

	private static final JsonNode IDLE = JsonText.read("{\"containerCpuMsPerSecond\": {\"api\": 10.0}}");

	// 7,800 ms less 30 s at 10 ms a second, over the pass's 30,000 ms
	@Test
	void netCores_aContainerThePassRead_isItsCpuLessItsCpuAtRestOverThePass() {
		JsonNode pass = JsonText.read("{\"containerCpuMs\": {\"api\": 7800.0}}");

		OptionalDouble cores = CpuByLoad.netCores(pass, IDLE, "api");

		assertThat(cores.orElseThrow()).isCloseTo(0.25, within(1e-12));
	}

	@Test
	void netCores_aContainerThePassDidNotRead_isEmpty() {
		JsonNode pass = JsonText.read("{\"containerCpuMs\": {\"api\": 7800.0}}");

		OptionalDouble cores = CpuByLoad.netCores(pass, IDLE, "postgres");

		assertThat(cores).isEmpty();
	}

}
