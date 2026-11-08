package io.archsim.engine.kernel;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;

/**
 * Something that happens at a point of simulated time: it may happen (not triggered), is
 * going to happen (triggered: scheduled, its callbacks about to run) or has happened
 * (processed: its callbacks ran). Code that waits for an event continues in a callback,
 * as a SimPy process resumes when the event it yielded is processed.
 */
public class Event {

	private final Simulation simulation;

	private @Nullable List<Consumer<Event>> callbacks = new ArrayList<>();

	private boolean triggered;

	private double time;

	private int priority;

	private long order;

	Event(Simulation simulation) {
		this.simulation = simulation;
	}

	/**
	 * Whether the event has been triggered: it is scheduled, or already processed.
	 * @return {@code true} once triggered
	 */
	public final boolean triggered() {
		return this.triggered;
	}

	/**
	 * Whether the event has been processed: its callbacks ran.
	 * @return {@code true} once processed
	 */
	public final boolean processed() {
		return this.callbacks == null;
	}

	/**
	 * Continues with {@code next} once the event has happened, as a SimPy process yields
	 * it: at once if the event has been processed already, else when it is.
	 * @param next what to do then
	 */
	public final void then(Runnable next) {
		if (this.callbacks == null) {
			next.run();
		}
		else {
			this.callbacks.add((event) -> next.run());
		}
	}

	/**
	 * Triggers the event now, at normal priority.
	 * @throws IllegalStateException when it has been triggered already
	 */
	final void trigger() {
		if (this.triggered) {
			throw new IllegalStateException("the event has already been triggered");
		}
		scheduleTriggered(Simulation.NORMAL, 0);
	}

	/**
	 * Triggers the event and puts it on the queue.
	 * @param priority its priority
	 * @param delay milliseconds from now
	 */
	final void scheduleTriggered(int priority, double delay) {
		this.triggered = true;
		this.simulation.schedule(this, priority, delay);
	}

	final void addCallback(Consumer<Event> callback) {
		if (this.callbacks == null) {
			throw new IllegalStateException("the event has already been processed");
		}
		this.callbacks.add(callback);
	}

	final void removeCallback(Consumer<Event> callback) {
		if (this.callbacks != null) {
			this.callbacks.remove(callback);
		}
	}

	final void scheduleAt(double time, int priority, long order) {
		this.time = time;
		this.priority = priority;
		this.order = order;
	}

	/**
	 * Runs the event's callbacks, in the order they were added; after this it has been
	 * processed.
	 */
	final void process() {
		List<Consumer<Event>> waiting = this.callbacks;
		this.callbacks = null;
		if (waiting != null) {
			for (Consumer<Event> callback : waiting) {
				callback.accept(this);
			}
		}
	}

	final double time() {
		return this.time;
	}

	/**
	 * The order events are processed in: by time, then priority, then the order they were
	 * scheduled in, as SimPy's heap of {@code (time, priority, id)} orders them
	 * ({@code 0.0} and {@code -0.0} are the same time there, as here).
	 * @param first one event
	 * @param second another
	 * @return a negative number when {@code first} comes first, positive when it comes
	 * after, 0 for the same event
	 */
	static int order(Event first, Event second) {
		if (first.time != second.time) {
			return (first.time < second.time) ? -1 : 1;
		}
		if (first.priority != second.priority) {
			return Integer.compare(first.priority, second.priority);
		}
		return Long.compare(first.order, second.order);
	}

}
