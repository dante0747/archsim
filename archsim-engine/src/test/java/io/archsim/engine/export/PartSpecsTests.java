package io.archsim.engine.export;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.settings.LenientSettings;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A part's CPU, memory and storage, as container resources.
 */
class PartSpecsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void kubernetesResources_fractionalSpecs_areMillicoresAndMebibytes() {
		PartSpecs specs = specs("{\"cpuCores\": 0.5, \"memoryGib\": 1.5}");

		assertThat(specs.kubernetesResources(null)).contains(object("""
				{"requests": {"cpu": "500m", "memory": "1536Mi"}, "limits": {"cpu": "500m", "memory": "1536Mi"}}"""));
	}

	@Test
	void kubernetesResources_needBelowItsCores_requestsTheNeedRoundedUpToTenMillicores() {
		PartSpecs specs = specs("{\"cpuCores\": 2, \"memoryGib\": 4}");

		assertThat(specs.kubernetesResources(0.123)).contains(object("""
				{"requests": {"cpu": "130m", "memory": "4Gi"}, "limits": {"cpu": "2", "memory": "4Gi"}}"""));
	}

	@Test
	void kubernetesResources_noCpuOrMemory_isNone() {
		assertThat(specs("{\"storageGib\": 10}").kubernetesResources(null)).isEmpty();
	}

	@Test
	void composeResources_wholeMemory_isGigabytesWithTheCpuAsText() {
		PartSpecs specs = specs("{\"cpuCores\": 0.25, \"memoryGib\": 2}");

		assertThat(specs.composeResources()).contains(object("""
				{"limits": {"cpus": "0.25", "memory": "2G"}, "reservations": {"cpus": "0.25", "memory": "2G"}}"""));
	}

	@Test
	void of_specsThatAreBooleansOrNotAboveZero_hasNone() {
		PartSpecs specs = specs("{\"cpuCores\": true, \"memoryGib\": 0, \"storageGib\": -1}");

		assertThat(specs).isEqualTo(new PartSpecs(null, null, null));
	}

	private static PartSpecs specs(String config) {
		return PartSpecs.of(LenientSettings.of(JSON.readTree(config)));
	}

	private static ObjectNode object(String json) {
		return (ObjectNode) JSON.readTree(json);
	}

}
