package io.archsim.engine.components.queue;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.kernel.Steps;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.runtime.KeyShards;

/**
 * A partitioned queue's ordered logs: a message goes to its key's partition, or to the
 * partitions in turn, and each partition is consumed by one consumer (consumer {@code i}
 * owns the partitions {@code p} with {@code p mod consumers = i}), one message at a time
 * or in polls. A hot partition backs up behind its one consumer.
 */
final class Partitions implements Holding {

	private final Simulation simulation;

	private final Timeline timeline;

	private final List<Deque<Message>> logs;

	private final boolean byKey;

	private final int consumers;

	/** What wakes each consumer when a message reaches one of its partitions. */
	private final Signal[] wake;

	/** The longest partition in each bucket. */
	private final long[] longest;

	private int turn;

	/**
	 * Empty partitions.
	 * @param simulation the run
	 * @param timeline the run's timeline
	 * @param layout the partitions, how messages are spread over them and their consumers
	 */
	Partitions(Simulation simulation, Timeline timeline, Layout layout) {
		this.simulation = simulation;
		this.timeline = timeline;
		this.byKey = layout.byKey();
		this.consumers = layout.consumers();
		this.logs = new ArrayList<>(layout.partitions());
		for (int partition = 0; partition < layout.partitions(); partition++) {
			this.logs.add(new ArrayDeque<>());
		}
		this.wake = new Signal[this.consumers];
		for (int consumer = 0; consumer < this.consumers; consumer++) {
			this.wake[consumer] = simulation.event();
		}
		this.longest = new long[timeline.buckets()];
	}

	@Override
	public void add(Message message, double now) {
		int partition = partitionOf(message);
		Deque<Message> log = this.logs.get(partition);
		log.addLast(message);
		int bucket = this.timeline.index(now);
		if (log.size() > this.longest[bucket]) {
			this.longest[bucket] = log.size();
		}
		Signal owner = this.wake[partition % this.consumers];
		if (!owner.triggered()) {
			owner.succeed();
		}
	}

	@Override
	public void startConsumers(Consuming consuming) {
		for (int consumer = 0; consumer < this.consumers; consumer++) {
			int[] owned = owned(consumer);
			int index = consumer;
			if (consuming.polls()) {
				PollingConsumer polling = new PollingConsumer(consuming, new Source(index, owned));
				this.simulation.start((finish) -> polling.poll());
			}
			else {
				OneAtATime taking = new OneAtATime(consuming, index, owned);
				this.simulation.start((finish) -> taking.takeNext());
			}
		}
	}

	/**
	 * The longest partition in each bucket.
	 * @return messages per bucket of the whole timeline
	 */
	long[] longestPerBucket() {
		return this.longest.clone();
	}

	/**
	 * The longest partition now.
	 * @return its messages
	 */
	int longestNow() {
		return this.logs.stream().mapToInt(Deque::size).max().orElse(0);
	}

	private int partitionOf(Message message) {
		if (this.byKey) {
			return KeyShards.of(message.key(), this.logs.size());
		}
		int partition = this.turn;
		this.turn = (partition + 1) % this.logs.size();
		return partition;
	}

	private int[] owned(int consumer) {
		List<Integer> owned = new ArrayList<>();
		for (int partition = consumer; partition < this.logs.size(); partition += this.consumers) {
			owned.add(partition);
		}
		return owned.stream().mapToInt(Integer::intValue).toArray();
	}

	private Signal waitForMessages(int consumer) {
		Signal arrived = this.simulation.event();
		this.wake[consumer] = arrived;
		return arrived;
	}

	/**
	 * The partitions, how messages are spread over them, and their consumers.
	 *
	 * @param partitions how many partitions
	 * @param byKey whether a message goes to its key's partition, rather than in turn
	 * @param consumers the consumers that own them
	 */
	record Layout(int partitions, boolean byKey, int consumers) {
	}

	/**
	 * A consumer that takes one message at a time from its partitions, each in turn.
	 */
	private final class OneAtATime {

		private final Consuming consuming;

		private final int index;

		private final int[] owned;

		private int turn;

		OneAtATime(Consuming consuming, int index, int[] owned) {
			this.consuming = consuming;
			this.index = index;
			this.owned = owned;
		}

		// message after message, in a loop while each is processed at once, then a wait
		// for the next
		void takeNext() {
			Steps.each(this::removeNext, this.consuming.deliveries()::process,
					() -> waitForMessages(this.index).then(this::takeNext));
		}

		// the next message from the first of its partitions, from where it left off, that
		// has one; null when none has
		private @Nullable Message removeNext() {
			int count = this.owned.length;
			for (int step = 0; step < count; step++) {
				Deque<Message> log = Partitions.this.logs.get(this.owned[(this.turn + step) % count]);
				if (!log.isEmpty()) {
					this.turn = (this.turn + step + 1) % count;
					return log.removeFirst();
				}
			}
			return null;
		}

	}

	/**
	 * What a polling consumer of the partitions polls: each of its partitions' whole
	 * backlog.
	 */
	private final class Source implements PollingConsumer.Source {

		private final int index;

		private final int[] owned;

		Source(int index, int[] owned) {
			this.index = index;
			this.owned = owned;
		}

		@Override
		public Deque<List<Message>> poll() {
			Deque<List<Message>> work = new ArrayDeque<>();
			for (int partition : this.owned) {
				Deque<Message> log = Partitions.this.logs.get(partition);
				if (!log.isEmpty()) {
					work.addLast(List.copyOf(log));
					log.clear();
				}
			}
			return work;
		}

		@Override
		public Signal waitForMessages() {
			return Partitions.this.waitForMessages(this.index);
		}

	}

}
