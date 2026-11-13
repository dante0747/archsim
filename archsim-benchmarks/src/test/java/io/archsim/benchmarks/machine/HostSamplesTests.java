package io.archsim.benchmarks.machine;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Windows' samples over a window: three samples a second apart, two logical processors as
 * the hypervisor counts them, the first sample's second starting before the window.
 */
class HostSamplesTests {

	private static final String SAMPLE = """
			{"t": %d, "processor information(_total)|actual frequency": %s,
			 "processor information(_total)|%% performance limit": 100,
			 "processor information(_total)|performance limit flags": %d,
			 "hyper-v hypervisor logical processor(hv lp 10)|%% total run time": 20,
			 "hyper-v hypervisor logical processor(hv lp 2)|%% total run time": %s,
			 "hyper-v hypervisor logical processor(_total)|%% total run time": 99}""";

	@Test
	void window_threeSamples_meansThoseWhollyInside() {
		JsonNode window = samples().window(1000, 4000).orElseThrow();

		assertThat(window.get("samples").intValue()).isEqualTo(2);
		assertThat(window.get("frequencyMHz").doubleValue()).isEqualTo(3050.0);
	}

	// by the number each names, not as text: 2 before 10; the total left out
	@Test
	void window_logicalProcessors_areInTheirNumbersOrder() {
		JsonNode window = samples().window(1000, 4000).orElseThrow();

		assertThat(window.get("logicalProcessorBusyPct")).isEqualTo(JsonText.read("[70.0, 20.0]"));
	}

	@Test
	void window_logicalProcessors_addUpToTheBusyProcessors() {
		JsonNode window = samples().window(1000, 4000).orElseThrow();

		assertThat(window.get("busyLogicalProcessors").doubleValue()).isEqualTo(0.9);
	}

	@Test
	void window_limitFlags_areEachOneSeenInOrder() {
		JsonNode window = samples().window(0, 4000).orElseThrow();

		assertThat(window.get("performanceLimitFlags")).isEqualTo(JsonText.read("[0, 2]"));
	}

	@Test
	void window_noSampleInside_isNone() {
		assertThat(samples().window(5000, 9000)).isEmpty();
	}

	// the script's noise left out
	@Test
	void add_aLineThatIsNotJson_isNoSample() {
		HostSamples samples = new HostSamples();
		samples.add("WARNING: a counter is missing");

		assertThat(samples.window(0, 1e12)).isEmpty();
	}

	private static HostSamples samples() {
		HostSamples samples = new HostSamples();
		samples.add(SAMPLE.formatted(1500, "2900", 2, "50").replace("\n", " "));
		samples.add(SAMPLE.formatted(2000, "3000", 0, "60").replace("\n", " "));
		samples.add(SAMPLE.formatted(3000, "3100", 0, "80").replace("\n", " "));
		return samples;
	}

}
