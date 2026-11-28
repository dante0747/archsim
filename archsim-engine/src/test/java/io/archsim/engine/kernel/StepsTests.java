package io.archsim.engine.kernel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Steps that finish at once go on in a loop, at any number, in their order; steps that
 * finish later go on from their finish; and what follows runs once, after the last.
 */
class StepsTests {

	@Test
	void each_aMillionStepsThatFinishAtOnce_takesThemAllInOrderThenGoesOn() {
		List<String> done = new ArrayList<>();
		AtomicInteger next = new AtomicInteger();
		AtomicInteger outOfOrder = new AtomicInteger();
		Iterator<Integer> items = IntStream.range(0, 1_000_000).boxed().iterator();

		Steps.each(() -> items.hasNext() ? items.next() : null, (item, finish) -> {
			if (item != next.getAndIncrement()) {
				outOfOrder.incrementAndGet();
			}
			finish.run();
		}, () -> done.add("done after " + next.get() + " steps, " + outOfOrder.get() + " out of order"));

		assertThat(done).containsExactly("done after 1000000 steps, 0 out of order");
	}

	@Test
	void each_stepsThatFinishLater_goOnFromEachFinish() {
		List<String> taken = new ArrayList<>();
		List<Runnable> waiting = new ArrayList<>();
		Iterator<String> items = List.of("a", "b").iterator();

		Steps.each(() -> items.hasNext() ? items.next() : null, (item, finish) -> {
			taken.add(item);
			waiting.add(finish);
		}, () -> taken.add("done"));
		waiting.getFirst().run();
		waiting.getLast().run();

		assertThat(taken).containsExactly("a", "b", "done");
	}

	@Test
	void each_noItem_goesOnAtOnce() {
		List<String> taken = new ArrayList<>();

		Steps.each(() -> null, (Object item, Runnable finish) -> taken.add("step"), () -> taken.add("done"));

		assertThat(taken).containsExactly("done");
	}

}
