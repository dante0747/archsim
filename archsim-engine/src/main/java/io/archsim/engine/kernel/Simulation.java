package io.archsim.engine.kernel;

import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * The simulated clock and its events, processed one at a time in SimPy 4.1.2's order: by
 * time, then priority (a routine starts urgently, everything else normally), then the
 * order they were scheduled in. Time is in milliseconds. One thread runs a simulation.
 */
public final class Simulation {

	/** The priority of the event that stopped a chunked run, when it is put back. */
	static final int STOPPED = -1;

	/** The priority a routine starts with. */
	static final int URGENT = 0;

	/** The priority of every other event. */
	static final int NORMAL = 1;

	private final PriorityQueue<Event> queue = new PriorityQueue<>(Event::order);

	private double now;

	private long scheduled;

	/**
	 * The current simulated time.
	 * @return milliseconds since the start
	 */
	public double now() {
		return this.now;
	}

	/**
	 * A new event that code triggers with {@link Signal#succeed()}.
	 * @return the event, not yet triggered
	 */
	public Signal event() {
		return new Signal(this);
	}

	/**
	 * A new event that code triggers with a value.
	 * @param <T> the value's type
	 * @return the event, not yet triggered
	 */
	public <T> Promise<T> promise() {
		return new Promise<>(this);
	}

	/**
	 * An event that happens after a delay.
	 * @param delay milliseconds from now, at least 0
	 * @return the event, scheduled
	 */
	public Timeout timeout(double delay) {
		return new Timeout(this, delay);
	}

	/**
	 * Starts a routine: its body runs when the routine starts, ahead of every normal
	 * event due at the same time, and the routine happens when its body calls the finish
	 * it is given.
	 * @param body what the routine does
	 * @return the routine, an event other code can wait for
	 */
	public Routine start(Routine.Body body) {
		return new Routine(this, body);
	}

	/**
	 * An event that happens once any of {@code events} has, at once when there are none.
	 * @param events the events
	 * @return the condition
	 */
	public Condition anyOf(List<? extends Event> events) {
		return new Condition(this, List.copyOf(events), Condition.ANY);
	}

	/**
	 * An event that happens once all of {@code events} have, at once when there are none.
	 * @param events the events
	 * @return the condition
	 */
	public Condition allOf(List<? extends Event> events) {
		return new Condition(this, List.copyOf(events), Condition.ALL);
	}

	/**
	 * Processes the next event.
	 * @return {@code false} when no event is left
	 */
	public boolean step() {
		Event next = this.queue.poll();
		if (next == null) {
			return false;
		}
		this.now = next.time();
		next.process();
		return true;
	}

	/**
	 * Processes events until simulated time {@code until}, checking every {@code every}
	 * events: SimPy's run until a time, as the Python engine ran each chunk of a run. A
	 * stop event due at {@code until} ends the chunk; like SimPy's, it goes back on the
	 * queue ahead of everything else at that time, so the next chunk processes it first.
	 * @param until the simulated time to stop at, later than now
	 * @param every how many events between two checks, at least 1
	 * @param check what to run every {@code every} events (it may throw to stop the run)
	 * @return how many events were processed, this chunk's stop event not counted and the
	 * previous chunk's, processed first, counted
	 * @throws IllegalArgumentException for a time not later than now, or fewer than one
	 * event between checks
	 */
	public long runUntil(double until, int every, Runnable check) {
		if (until <= this.now || every < 1) {
			throw new IllegalArgumentException(
					"a chunk runs to a later time, checking every 1 or more events; got until " + until + " at "
							+ this.now + ", every " + every);
		}
		Signal stop = new Signal(this);
		stop.scheduleTriggered(URGENT, until - this.now);
		long done = 0;
		int left = every;
		while (true) {
			// the stop event is on the queue until it is reached
			Event next = Objects.requireNonNull(this.queue.poll());
			this.now = next.time();
			if (next == stop) {
				schedule(stop, STOPPED, 0);
				return done + (every - left);
			}
			next.process();
			left--;
			if (left == 0) {
				done += every;
				left = every;
				check.run();
			}
		}
	}

	/**
	 * Puts an event on the queue.
	 * @param event the event, triggered
	 * @param priority its priority
	 * @param delay milliseconds from now
	 */
	void schedule(Event event, int priority, double delay) {
		event.scheduleAt(this.now + delay, priority, this.scheduled++);
		this.queue.add(event);
	}

}
