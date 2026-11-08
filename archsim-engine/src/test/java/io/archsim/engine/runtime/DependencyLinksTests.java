package io.archsim.engine.runtime;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.RandomStreams;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

import static org.assertj.core.api.Assertions.assertThat;

/** A part's links for each kind of request, in the order it calls them. */
class DependencyLinksTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final Timeline timeline = new Timeline(1_000, 0, 1_000);

	private final SimContext context = new SimContext(new Simulation(), new RandomStreams(7), this.timeline,
			RecordCodec.read(JSON.readTree("{}"), Workload.class));

	@Test
	void carrying_linksOfSeveralOrders_listsThemByOrderThenId() {
		link("b", "{\"order\": 1}");
		link("c", "{\"order\": 0}");
		link("a", "{\"order\": 1}");

		assertThat(new DependencyLinks(this.context, "svc").carrying(RequestType.READ)).extracting(EdgeRuntime::id)
			.containsExactly("c", "a", "b");
	}

	@Test
	void carrying_aLinkForOtherRequests_leavesItOut() {
		link("reads", "{\"requestTypes\": [\"read\"]}");
		link("writes", "{\"requestTypes\": [\"write\"]}");

		assertThat(new DependencyLinks(this.context, "svc").carrying(RequestType.WRITE)).extracting(EdgeRuntime::id)
			.containsExactly("writes");
	}

	// a link from the part, svc, to a database
	private void link(String id, String config) {
		EdgeSpec spec = RecordCodec.read((ObjectNode) JSON
			.readTree("{\"id\": \"" + id + "\", \"source\": \"svc\", \"target\": \"db\", \"config\": " + config + "}"),
				EdgeSpec.class);
		this.context.addEdge(new EdgeRuntime(spec, this.timeline, this.context.stream("edge:" + id)));
	}

}
