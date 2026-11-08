package io.archsim.engine.components.queue;

import java.util.OptionalDouble;
import java.util.OptionalInt;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.NodeMetrics;
import io.archsim.engine.runtime.Request;
import io.archsim.model.document.RequestType;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A queue's backlog and its memory alarm, as the Python engine 0.16.0 raises and lifts
 * it: at the threshold, and below 90% of it.
 */
class BacklogTests {

	private final Simulation simulation = new Simulation();

	private final NodeMetrics metrics = new NodeMetrics(new Timeline(1000, 0, 1000), OptionalInt.empty());

	private final Backlog backlog = new Backlog(this.simulation, this.metrics, new SharedStore(this.simulation, 1),
			OptionalDouble.of(10));

	@Test
	void add_reachingTheThreshold_raisesTheAlarm() {
		addMessages(10);

		assertThat(this.backlog.blocked()).isTrue();
		assertThat(this.metrics.counters()).hasEntrySatisfying(Backlog.MEMORY_ALARMS,
				(alarms) -> assertThat(alarms.total()).isOne());
	}

	@Test
	void taken_downToNinetyPercent_keepsTheAlarm() {
		addMessages(10);

		this.backlog.taken(0);

		assertThat(this.backlog.blocked()).isTrue();
	}

	@Test
	void taken_belowNinetyPercent_liftsTheAlarm() {
		addMessages(10);
		this.backlog.taken(0);

		this.backlog.taken(0);

		assertThat(this.backlog.blocked()).isFalse();
	}

	@Test
	void whenUnblocked_whileBlocked_waitsForTheAlarmToLift() {
		addMessages(10);
		boolean[] through = new boolean[1];
		this.backlog.whenUnblocked(() -> through[0] = true);

		this.backlog.taken(0);
		this.backlog.taken(0);
		this.simulation.runUntil(1, 1000, () -> {
		});

		assertThat(through[0]).isTrue();
	}

	private void addMessages(int count) {
		Request publisher = Request.withoutTrace(new Request.Arrival(1, RequestType.WRITE, 0, 0, true));
		for (int message = 0; message < count; message++) {
			this.backlog.add(new Message(publisher, 0, 0), 0);
		}
	}

}
