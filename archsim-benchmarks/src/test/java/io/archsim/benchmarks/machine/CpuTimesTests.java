package io.archsim.benchmarks.machine;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Docker's VM's CPU time, from {@code /proc/stat}.
 */
class CpuTimesTests {

	@Test
	void of_theCpuLine_isTheStealAndTheFirstEightCounters() {
		assertThat(CpuTimes.of("cpu  10 0 20 300 5 0 1 4 7 0\ncpu0 1 0 2 30 0 0 0 0 0 0\n"))
			.contains(new CpuTimes(4, 340));
	}

	@Test
	void of_noCpuLine_isNone() {
		assertThat(CpuTimes.of("sh: cat: not found\n")).isEmpty();
	}

	@Test
	void stealSince_anEarlierReading_isTheShareOfTheTimeBetween() {
		assertThat(new CpuTimes(14, 540).stealSince(new CpuTimes(4, 340))).isEqualTo(0.05);
	}

}
