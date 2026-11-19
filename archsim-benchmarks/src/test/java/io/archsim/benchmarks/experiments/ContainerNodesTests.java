package io.archsim.benchmarks.experiments;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class ContainerNodesTests {

	@ParameterizedTest
	@CsvSource({ "api-java, api", "api-go, api", "api-python, api", "chain-api, api", "api-ttl, api",
			"gateway, gateway", "redis-ttl, cache", "postgres, db", "api-python-cores, api", "api-python-wide, api",
			"api-go-pool, api" })
	void of_aHeldOutStacksContainer_isTheModelsNode(String container, String node) {
		assertThat(ContainerNodes.of(container)).contains(node);
	}

	@Test
	void of_aContainerTheModelHasNoNodeFor_isNone() {
		assertThat(ContainerNodes.of("kafka")).isEmpty();
	}

}
