package io.archsim.benchmarks.docker;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;

class CpusetsTests {

	private static final Cpusets COMPOSE = Cpusets.declaredIn(RepositoryLayout.current().compose());

	@ParameterizedTest
	@MethodSource("stacks")
	void of_eachStack_pinsEveryContainerAndK6(String stack) {
		Map<String, String> sets = COMPOSE.of(stack, Map.of());

		assertThat(sets.keySet()).containsAll(Stacks.standard().containers(stack)).contains("k6");
	}

	@Test
	void of_noVariables_isComposesDefaultsAndTheRunnersK6() {
		Map<String, String> sets = COMPOSE.of("postgres", Map.of());

		assertThat(sets).containsExactly(Map.entry("api", "2"), Map.entry("postgres", "4,5"),
				Map.entry("k6", "0,1,6,7"));
	}

	// k6 stays off the service's hyperthread sibling
	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "cache | 0,6,7", "kafka | 6,7" })
	void of_eachStack_givesK6TheCpusTheStackLeavesFree(String stack, String k6) {
		Map<String, String> sets = COMPOSE.of(stack, Map.of());

		assertThat(sets).containsEntry("k6", k6);
	}

	@Test
	void of_variablesSet_takesThem() {
		Map<String, String> sets = COMPOSE.of("kafka", Map.of("BENCH_CPUS_POSTGRES", "0-3", "BENCH_CPUS_K6", "4-7"));

		assertThat(sets).containsEntry("postgres", "0-3").containsEntry("k6", "4-7");
	}

	@ParameterizedTest
	@MethodSource("stacks")
	void of_aStacksDefaults_pinNoTwoContainersToTheSameCpu(String stack) {
		List<Integer> cpus = COMPOSE.of(stack, Map.of())
			.values()
			.stream()
			.flatMap((cpuset) -> Cpusets.cpus(cpuset).stream())
			.toList();

		assertThat(cpus).doesNotHaveDuplicates();
	}

	@Test
	void cpus_aList_isTheCpusItNames() {
		assertThat(Cpusets.cpus("0,1,3")).containsExactly(0, 1, 3);
	}

	@Test
	void cpus_aRange_isEveryCpuInIt() {
		assertThat(Cpusets.cpus("4-7")).containsExactly(4, 5, 6, 7);
	}

	static Stream<String> stacks() {
		return Experiments.standard().stacks().keySet().stream();
	}

}
