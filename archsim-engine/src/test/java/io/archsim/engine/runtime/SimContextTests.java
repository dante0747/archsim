package io.archsim.engine.runtime;

import java.util.List;
import java.util.OptionalInt;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.random.RandomStreams;
import io.archsim.model.document.HostSpec;
import io.archsim.model.document.NodeSpec;

import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/** A run's context beyond the recorded runs: its host comes before its parts. */
class SimContextTests {

	@Test
	void useHost_afterPartsWereAdded_isRefused() {
		SimContext context = new SimContext(new Simulation(), new RandomStreams(7),
				new Timeline(2_000.0, 1_000.0, 2_000.0), List.of());
		context.addComponent(new Idle(context));

		assertThatIllegalStateException()
			.isThrownBy(() -> context.useHost(new HostSpec(2, 2, 0.6, 1.0, 0.0, 0.0), (seconds) -> 0.0))
			.withMessage("a run is put on its host before its parts are added");
	}

	/**
	 * A part that answers every request at once.
	 */
	private static final class Idle extends RuntimeComponent {

		Idle(SimContext context) {
			super(context, new NodeSpec("idle", "external_api", "", null, JsonNodeFactory.instance.objectNode()),
					OptionalInt.empty());
		}

		@Override
		public void handle(Request request, Consumer<Outcome> done) {
			done.accept(Outcome.OK);
		}

	}

}
