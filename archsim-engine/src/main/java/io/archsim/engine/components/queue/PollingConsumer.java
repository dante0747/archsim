package io.archsim.engine.components.queue;

import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.function.DoubleSupplier;

import io.archsim.engine.kernel.Routine;
import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Steps;

/**
 * A consumer that polls in cycles, as Kafka's consumers do: it takes a poll's batches,
 * processes them in its slots (a batch's messages one after another), waits until all are
 * done, pays the fetch overhead, then polls again. Messages that arrive meanwhile wait
 * for the next poll; with no backlog it waits for the next message and takes it at once.
 */
final class PollingConsumer {

	private final Consuming consuming;

	private final Source source;

	/**
	 * A consumer.
	 * @param consuming how it processes messages
	 * @param source what it polls
	 */
	PollingConsumer(Consuming consuming, Source source) {
		this.consuming = consuming;
		this.source = source;
	}

	/**
	 * Polls, and goes on polling for the rest of the run.
	 */
	void poll() {
		Deque<List<Message>> work = this.source.poll();
		if (work.isEmpty()) {
			this.source.waitForMessages().then(this::poll);
			return;
		}
		int slots = Math.min(this.consuming.concurrency(), work.size());
		List<Routine> running = new ArrayList<>(slots);
		for (int slot = 0; slot < slots; slot++) {
			running.add(this.consuming.simulation().start((finish) -> takeBatches(work, finish)));
		}
		this.consuming.simulation().allOf(running).then(() -> {
			DoubleSupplier overhead = this.consuming.fetchOverheadMs();
			if (overhead == null) {
				poll();
				return;
			}
			this.consuming.simulation().after(overhead.getAsDouble(), this::poll);
		});
	}

	// a slot takes the poll's batches in turn until none is left, in a loop while each is
	// processed at once
	private void takeBatches(Deque<List<Message>> work, Runnable finish) {
		Steps.each(work::pollFirst, (batch, next) -> process(batch.iterator(), next), finish);
	}

	// a batch's messages one after another, in a loop while each is processed at once
	private void process(Iterator<Message> batch, Runnable next) {
		Steps.each(() -> batch.hasNext() ? batch.next() : null, this.consuming.deliveries()::process, next);
	}

	/**
	 * What a polling consumer takes its messages from.
	 */
	interface Source {

		/**
		 * One poll's batches, taken from the backlog.
		 * @return the batches; empty when nothing waits
		 */
		Deque<List<Message>> poll();

		/**
		 * Waits for a message to arrive.
		 * @return the signal the next message gives
		 */
		Signal waitForMessages();

	}

}
