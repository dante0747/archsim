package io.archsim.benchmarks.docker;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container's CPU and memory over a measured window, from its readings.
 */
class WindowResourcesTests {

	private static final long MIB = 1 << 20;

	@Test
	void of_threeReadings_isTheBusyCoresTheBusiestIntervalAndTheMostMemory() {
		ObjectNode window = WindowResources.of(threeReadings()).orElseThrow();

		// 6 s of CPU over 10 s; 4 s over the second 5 s
		assertThat(window).isEqualTo(
				JsonText.read("{\"cores\": 0.6, \"maxCores\": 0.8, \"workingSetMiB\": 120.0, \"anonMiB\": 90.0}"));
	}

	@Test
	void of_oneReading_isItsMemoryAlone() {
		ObjectNode window = WindowResources.of(threeReadings().subList(0, 1)).orElseThrow();

		assertThat(window).isEqualTo(JsonText.read("{\"workingSetMiB\": 100.0, \"anonMiB\": 80.0}"));
	}

	@Test
	void of_noReadings_isNothing() {
		assertThat(WindowResources.of(List.of())).isEmpty();
	}

	private static List<CgroupReading> threeReadings() {
		return List.of(new CgroupReading(0.0, 0L, 100 * MIB, 80 * MIB),
				new CgroupReading(5.0, 2_000_000L, 120 * MIB, 90 * MIB),
				new CgroupReading(10.0, 6_000_000L, 110 * MIB, 85 * MIB));
	}

}
