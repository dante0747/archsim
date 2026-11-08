package io.archsim.engine.simulator;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.RandomStreams;
import io.archsim.engine.runtime.SimContext;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Each part's behaviour by its config: a config no standard part has is refused. */
class PartsTests {

	@Test
	void build_aConfigNoPartTakes_isRefused() {
		Timeline timeline = new Timeline(2_000.0, 1_000.0, 2_000.0);
		SimContext context = new SimContext(new Simulation(), new RandomStreams(7), timeline,
				RecordCodec.read(JsonMapper.builder().build().readTree("{}"), Workload.class));
		NodeSpec node = new NodeSpec("odd", "odd", "", null, JsonNodeFactory.instance.objectNode());

		assertThatIllegalArgumentException()
			.isThrownBy(() -> Parts.build(context, node, new Unknown(), new ClientMetrics(timeline)))
			.withMessage("no part takes a Unknown");
	}

	/** A config of no standard part. */
	private record Unknown() {
	}

}
