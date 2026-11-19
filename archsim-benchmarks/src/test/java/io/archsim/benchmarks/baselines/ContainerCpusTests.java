package io.archsim.benchmarks.baselines;

import java.util.Map;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The CPUs a run's containers could use, from the committed runs'
 * {@code environment.json} and {@code compose.yaml}.
 */
class ContainerCpusTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Cpusets DECLARED = Cpusets.declaredIn(LAYOUT.compose());

	@Test
	void of_aRunThatRecordedItsCpusets_countsEachContainersCpus() {
		JsonNode environment = ResultFiles.read(LAYOUT.results().resolve("kafka/run-10/environment.json"))
			.orElseThrow();

		ContainerCpus cpus = ContainerCpus.of(environment, DECLARED, "kafka");

		assertThat(cpus.cpus()).isEqualTo(Map.of("producer", 1, "consumer", 1, "postgres", 2, "kafka", 2, "k6", 2));
		assertThat(cpus.source()).isEqualTo(ContainerCpus.RECORDED);
	}

	// the first runs' environment.json has no cpusets
	@Test
	void of_aRunFromBeforeCpusetsWereRecorded_countsTheOnesComposeDeclares() {
		JsonNode environment = ResultFiles.read(LAYOUT.results().resolve("pg-mixed/run-1/environment.json"))
			.orElseThrow();

		ContainerCpus cpus = ContainerCpus.of(environment, DECLARED, "postgres");

		assertThat(cpus.cpus()).isEqualTo(Map.of("api", 1, "postgres", 2, "k6", 4));
		assertThat(cpus.source()).isEqualTo(ContainerCpus.DECLARED);
	}

	@Test
	void of_aRunWithoutAnEnvironment_countsTheOnesComposeDeclares() {
		ContainerCpus cpus = ContainerCpus.of(PythonValues.none(), DECLARED, "postgres");

		assertThat(cpus.cpus()).containsEntry("postgres", 2);
	}

	@Test
	void of_theKafkaStack_putsWhatWorksBehindItsQueueOffTheRequestPath() {
		ContainerCpus cpus = ContainerCpus.of(PythonValues.none(), DECLARED, "kafka");

		assertThat(cpus.behindQueue()).containsExactlyInAnyOrder("consumer", "postgres");
	}

	@Test
	void of_aStackWithoutAQueue_putsNothingOffTheRequestPath() {
		ContainerCpus cpus = ContainerCpus.of(PythonValues.none(), DECLARED, "cache");

		assertThat(cpus.behindQueue()).isEmpty();
	}

}
