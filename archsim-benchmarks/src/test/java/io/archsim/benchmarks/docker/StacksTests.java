package io.archsim.benchmarks.docker;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Each stack's containers ({@code stacks.json}).
 */
class StacksTests {

	// k6 calls the first container, and calibration reads its CPUs against its /stats
	@ParameterizedTest
	@CsvSource({ "postgres, api", "cache, api", "kafka, producer", "java-mixed, api-java", "go-mixed, api-go",
			"python-mixed, api-python", "chain, gateway", "cache-ttl, api-ttl", "python-cores, api-python-cores",
			"python-wide, api-python-wide", "go-pool, api-go-pool" })
	void containers_eachStack_startWithTheServiceK6Calls(String stack, String service) {
		assertThat(Stacks.standard().containers(stack).getFirst()).isEqualTo(service);
	}

	// the second set's stacks: a service of their own and the shared PostgreSQL
	@ParameterizedTest
	@CsvSource({ "python-cores, api-python-cores", "python-wide, api-python-wide", "go-pool, api-go-pool" })
	void containers_aStackOfTheSecondSet_areItsServiceAndPostgres(String stack, String service) {
		assertThat(Stacks.standard().containers(stack)).containsExactly(service, "postgres");
	}

	@ParameterizedTest
	@ValueSource(strings = { "postgres", "java-mixed", "go-mixed", "python-mixed", "chain", "python-cores",
			"python-wide", "go-pool" })
	void callsPostgresEveryRequest_aStackWhoseServiceQueriesIt_isSo(String stack) {
		assertThat(Stacks.standard().callsPostgresEveryRequest(stack)).isTrue();
	}

	// the cache stacks query it on a miss, Kafka's in its consumers
	@ParameterizedTest
	@ValueSource(strings = { "cache", "cache-ttl", "kafka" })
	void callsPostgresEveryRequest_aStackBehindACacheOrAQueue_isNot(String stack) {
		assertThat(Stacks.standard().callsPostgresEveryRequest(stack)).isFalse();
	}

	// k6 gets the cores, as the VM numbers them, that no container has a CPU on: CPUs 6,7
	// on the Python stacks, and 0,1 too on go-pool as on go-mixed
	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "python-cores | 6,7", "python-wide | 6,7", "go-pool | 0,1,6,7" })
	void k6Cpus_aStackOfTheSecondSet_areTheOnesItsContainersLeave(String stack, String cpus) {
		assertThat(Stacks.standard().k6Cpus(stack)).isEqualTo(cpus);
	}

	// Kafka's consumer and the PostgreSQL it inserts into: no request waits for them
	@Test
	void behindQueue_theKafkaStack_isItsConsumerAndItsPostgres() {
		assertThat(Stacks.standard().behindQueue("kafka")).containsExactly("consumer", "postgres");
	}

	@ParameterizedTest
	@ValueSource(strings = { "postgres", "cache", "java-mixed", "go-mixed", "python-mixed", "chain", "cache-ttl",
			"python-cores", "python-wide", "go-pool" })
	void behindQueue_aStackWithoutAQueue_isEmpty(String stack) {
		assertThat(Stacks.standard().behindQueue(stack)).isEmpty();
	}

}
