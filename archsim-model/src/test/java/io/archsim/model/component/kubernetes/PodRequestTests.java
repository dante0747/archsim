package io.archsim.model.component.kubernetes;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.service.ServiceConfig;

import static org.assertj.core.api.Assertions.assertThat;

/** What each pod of a service asks of a cluster's nodes. */
class PodRequestTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final ClusterConfig CLUSTER = RecordCodec.read(
			(ObjectNode) JSON.readTree("{\"defaultCpuRequest\": 0.25, \"defaultMemoryRequestGib\": 1}"),
			ClusterConfig.class);

	@Test
	void of_aServiceSettingItsSize_requestsItsSize() {
		ServiceConfig service = service("{\"cpuCores\": 2, \"memoryGib\": 4}");

		assertThat(PodRequest.of(service, CLUSTER)).isEqualTo(new PodRequest(2, 4));
	}

	@Test
	void of_aServiceWithoutASize_requestsTheClustersDefaults() {
		assertThat(PodRequest.of(service("{}"), CLUSTER)).isEqualTo(new PodRequest(0.25, 1));
	}

	private static ServiceConfig service(String json) {
		ObjectNode config = (ObjectNode) JSON.readTree(json);
		config.put("platform", "kubernetes");
		return RecordCodec.read(config, ServiceConfig.class);
	}

}
