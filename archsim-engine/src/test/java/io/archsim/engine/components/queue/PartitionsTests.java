package io.archsim.engine.components.queue;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.MessageLosses;
import io.archsim.engine.runtime.Request;
import io.archsim.model.document.RequestType;

import static org.assertj.core.api.Assertions.assertThat;

/** A partitioned queue's logs: messages by key or in turn, the longest tracked. */
class PartitionsTests {

	private final Simulation simulation = new Simulation();

	private final Timeline timeline = new Timeline(1000, 0, 1000);

	@Test
	void add_inTurn_spreadsMessagesEvenly() {
		Partitions partitions = new Partitions(this.simulation, this.timeline, new Partitions.Layout(3, false, 3));

		for (int message = 0; message < 6; message++) {
			partitions.add(message(0), 0);
		}

		assertThat(partitions.longestNow()).isEqualTo(2);
	}

	@Test
	void add_byKey_putsAKeysMessagesInOnePartition() {
		Partitions partitions = new Partitions(this.simulation, this.timeline, new Partitions.Layout(3, true, 3));

		for (int message = 0; message < 6; message++) {
			partitions.add(message(41), 0);
		}

		assertThat(partitions.longestNow()).isEqualTo(6);
		assertThat(partitions.longestPerBucket()[0]).isEqualTo(6);
	}

	private static Message message(int key) {
		return new Message(
				Request.withoutTrace(new Request.Arrival(1, RequestType.WRITE, key, 0, true), new MessageLosses()), 0,
				0);
	}

}
