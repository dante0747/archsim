package io.archsim.engine.components.kubernetes;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.RandomStreams;
import io.archsim.engine.runtime.MessageLosses;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.kubernetes.ClusterConfig;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** A cluster beyond the recorded runs: no request reaches it. */
class ClusterRuntimeTests {

	@Test
	void handle_aRequest_isRefused() {
		JsonMapper json = JsonMapper.builder().build();
		SimContext context = new SimContext(new Simulation(), new RandomStreams(7),
				new Timeline(2_000.0, 1_000.0, 2_000.0), RecordCodec.read(json.readTree("{}"), Workload.class));
		ClusterRuntime cluster = new ClusterRuntime(context,
				new NodeSpec("k8s", "kubernetes_cluster", "", null, JsonNodeFactory.instance.objectNode()),
				RecordCodec.read((ObjectNode) json.readTree("{}"), ClusterConfig.class));
		Request request = Request.withoutTrace(new Request.Arrival(1, RequestType.READ, 0, 0.0, true),
				new MessageLosses());

		assertThatExceptionOfType(UnsupportedOperationException.class)
			.isThrownBy(() -> cluster.handle(request, (outcome) -> {
			}))
			.withMessage("a cluster has no ports, so no request reaches it");
	}

}
