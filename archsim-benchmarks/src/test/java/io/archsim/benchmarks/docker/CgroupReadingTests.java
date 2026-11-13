package io.archsim.benchmarks.docker;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container's cgroup v2 counters at one moment.
 */
class CgroupReadingTests {

	private static final long MIB = 1 << 20;

	// cpu.stat, memory.stat and memory.current, as one cat of the three prints them
	private static final String COUNTERS = """
			usage_usec 2500000
			user_usec 2000000
			system_usec 500000
			anon 104857600
			file 52428800
			active_file 20971520
			inactive_file 31457280
			shmem 0
			188743680
			""";

	@Test
	void of_theCounters_takesTheCpuTime() {
		CgroupReading reading = CgroupReading.of(10.0, COUNTERS);

		assertThat(reading.usageUsec()).isEqualTo(2_500_000L);
	}

	@Test
	void of_theCounters_takesTheAnonymousMemory() {
		CgroupReading reading = CgroupReading.of(10.0, COUNTERS);

		assertThat(reading.anon()).isEqualTo(100 * MIB);
	}

	// memory.current less inactive_file, as Kubernetes and cAdvisor count a working set
	@Test
	void of_theCounters_takesTheWorkingSetWithoutReclaimableCache() {
		CgroupReading reading = CgroupReading.of(10.0, COUNTERS);

		assertThat(reading.workingSet()).isEqualTo(188_743_680L - 31_457_280L);
	}

	@Test
	void of_noCounters_readsNothing() {
		CgroupReading reading = CgroupReading.of(1.0, "");

		assertThat(reading).isEqualTo(new CgroupReading(1.0, null, null, null));
	}

}
