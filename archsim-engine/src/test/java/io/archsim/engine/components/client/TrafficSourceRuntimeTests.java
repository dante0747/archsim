package io.archsim.engine.components.client;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.RandomStreams;
import io.archsim.engine.runtime.Request;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.client.TrafficSourceConfig;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * A client group beyond the frozen engine's recorded runs.
 */
class TrafficSourceRuntimeTests {

	@Test
	void handle_aCallToAClientGroup_isRefused() {
		Timeline timeline = new Timeline(1000, 0, 1000);
		Workload workload = RecordCodec.read(JsonMapper.builder().build().readTree("{}"), Workload.class);
		SimContext context = new SimContext(new Simulation(), new RandomStreams(7), timeline, workload);
		NodeSpec node = RecordCodec.read(
				JsonMapper.builder().build().readTree("{\"id\": \"clients\", \"type\": \"traffic_source\"}"),
				NodeSpec.class);
		TrafficSourceRuntime clients = new TrafficSourceRuntime(context, node,
				RecordCodec.read(JsonMapper.builder().build().readTree("{}"), TrafficSourceConfig.class),
				new ClientMetrics(timeline));
		Request request = Request.withoutTrace(new Request.Arrival(1, RequestType.READ, 0, 0, true));

		assertThatIllegalStateException().isThrownBy(() -> clients.handle(request, (outcome) -> {
		})).withMessage("a traffic source cannot be the target of a call");
	}

}
