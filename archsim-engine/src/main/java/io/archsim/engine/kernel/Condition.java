package io.archsim.engine.kernel;

import java.util.List;
import java.util.function.Consumer;

/**
 * An event that happens once some of a list of events have: any one of them, or all. As
 * in SimPy, it counts each event as that event is processed (at once for one processed
 * already), triggers itself when the count is enough, and when it is processed stops
 * listening to the events still to come.
 */
public final class Condition extends Event {

	/** Enough once one event happened. */
	static final Rule ANY = (events, count) -> count > 0;

	/** Enough once every event happened. */
	static final Rule ALL = (events, count) -> count == events;

	private final List<Event> events;

	private final Rule rule;

	private final Consumer<Event> checkCallback = this::check;

	private int count;

	Condition(Simulation simulation, List<Event> events, Rule rule) {
		super(simulation);
		this.events = events;
		this.rule = rule;
		if (events.isEmpty()) {
			trigger();
			return;
		}
		for (Event event : events) {
			if (event.processed()) {
				check(event);
			}
			else {
				event.addCallback(this.checkCallback);
			}
		}
		addCallback((event) -> stopChecking());
	}

	private void check(Event event) {
		if (triggered()) {
			return;
		}
		this.count++;
		if (this.rule.enough(this.events.size(), this.count)) {
			trigger();
		}
	}

	private void stopChecking() {
		for (Event waited : this.events) {
			waited.removeCallback(this.checkCallback);
			if (waited instanceof Condition nested) {
				nested.stopChecking();
			}
		}
	}

	/**
	 * When a condition has had enough of its events.
	 */
	@FunctionalInterface
	interface Rule {

		/**
		 * Whether {@code count} of {@code events} events is enough.
		 * @param events how many events the condition waits on
		 * @param count how many have happened
		 * @return {@code true} when the condition holds
		 */
		boolean enough(int events, int count);

	}

}
