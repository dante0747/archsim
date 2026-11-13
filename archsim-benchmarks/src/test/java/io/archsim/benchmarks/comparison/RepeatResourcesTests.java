package io.archsim.benchmarks.comparison;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

class RepeatResourcesTests {

	// 100 req/s over the 20 s window at 3 ms of the service's CPU each: 0.3 busy cores
	@Test
	void of_aRepeatBeforeSampling_scalesItsSharesByTheServicesOwnCpu() {
		ObjectNode old = Repeats.repeat(2.0, 2000).put("serviceCpuMsPerRequest", 3.0);
		old.putObject("containerCpuShare").put("api", 0.25).put("postgres", 0.1);

		JsonNode resources = RepeatResources.of(old).orElseThrow();

		assertThat(resources.get("method").stringValue()).isEqualTo("scaled");
		assertThat(resources.at("/containers/api/cores").doubleValue()).isEqualTo(0.3);
		assertThat(resources.at("/containers/postgres/cores").doubleValue()).isEqualTo(0.12);
	}

	@Test
	void of_aSampledRepeat_isWhatItSampled() {
		ObjectNode sampled = Repeats.repeat(2.0);
		sampled.set("resources", JsonText.read("""
				{"method": "window", "containers": {"api": {"cores": 0.31}}}"""));

		Optional<JsonNode> resources = RepeatResources.of(sampled);

		assertThat(resources).contains(sampled.get("resources"));
	}

	@Test
	void of_aRepeatWithNothingRecorded_isEmpty() {
		Optional<JsonNode> resources = RepeatResources.of(Repeats.repeat(2.0));

		assertThat(resources).isEmpty();
	}

}
