package io.archsim.benchmarks.docker;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each stack's containers ({@code stacks.json}).
 */
class StacksTests {

	// k6 calls the first container, and calibration reads its CPUs against its /stats
	@ParameterizedTest
	@CsvSource({ "postgres, api", "cache, api", "kafka, producer", "java-mixed, api-java", "go-mixed, api-go",
			"python-mixed, api-python", "chain, gateway", "cache-ttl, api-ttl" })
	void containers_eachStack_startWithTheServiceK6Calls(String stack, String service) {
		assertThat(Stacks.standard().containers(stack).getFirst()).isEqualTo(service);
	}

}
