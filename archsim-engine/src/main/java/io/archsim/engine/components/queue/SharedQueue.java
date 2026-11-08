package io.archsim.engine.components.queue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Simulation;

/**
 * One shared queue whose consumers poll in cycles, each poll taking a message per slot; a
 * message that arrives wakes one consumer waiting for one.
 */
final class SharedQueue implements Holding {

	private final Simulation simulation;

	private final int consumers;

	private final Deque<Message> waiting = new ArrayDeque<>();

	/** The consumers waiting for a message, the longest waiting first. */
	private final Deque<Signal> idle = new ArrayDeque<>();

	/**
	 * An empty queue.
	 * @param simulation the run
	 * @param consumers the consumers that poll it
	 */
	SharedQueue(Simulation simulation, int consumers) {
		this.simulation = simulation;
		this.consumers = consumers;
	}

	@Override
	public void add(Message message, double now) {
		this.waiting.addLast(message);
		while (!this.idle.isEmpty()) {
			Signal consumer = this.idle.removeFirst();
			if (!consumer.triggered()) {
				consumer.succeed();
				break;
			}
		}
	}

	@Override
	public void startConsumers(Consuming consuming) {
		for (int consumer = 0; consumer < this.consumers; consumer++) {
			PollingConsumer polling = new PollingConsumer(consuming, new Source(consuming.concurrency()));
			this.simulation.start((finish) -> polling.poll());
		}
	}

	/**
	 * What a consumer of the shared queue polls: a message per slot.
	 */
	private final class Source implements PollingConsumer.Source {

		private final int slots;

		Source(int slots) {
			this.slots = slots;
		}

		@Override
		public Deque<List<Message>> poll() {
			Deque<List<Message>> work = new ArrayDeque<>();
			while (!SharedQueue.this.waiting.isEmpty() && work.size() < this.slots) {
				work.addLast(List.of(SharedQueue.this.waiting.removeFirst()));
			}
			return work;
		}

		@Override
		public Signal waitForMessages() {
			Signal arrived = SharedQueue.this.simulation.event();
			SharedQueue.this.idle.addLast(arrived);
			return arrived;
		}

	}

}
