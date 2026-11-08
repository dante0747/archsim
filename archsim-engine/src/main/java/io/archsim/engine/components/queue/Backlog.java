package io.archsim.engine.components.queue;

import java.util.OptionalDouble;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.kernel.Signal;
import io.archsim.engine.kernel.Simulation;
import io.archsim.engine.runtime.NodeMetrics;

/**
 * A queue's backlog: the messages published and not taken by a consumer yet, where they
 * are held, and its memory alarm, which blocks publishers once the backlog reaches the
 * threshold until it falls below 90% of it.
 */
final class Backlog {

	/** The counter of memory alarms. */
	static final String MEMORY_ALARMS = "memoryAlarms";

	/** The share of the threshold the backlog falls below to lift the alarm. */
	private static final double ALARM_CLEARS_AT = 0.9;

	private final Simulation simulation;

	private final NodeMetrics metrics;

	private final Holding holding;

	private final OptionalDouble threshold;

	private int depth;

	private boolean blocked;

	private @Nullable Signal unblocked;

	/**
	 * An empty backlog.
	 * @param simulation the run
	 * @param metrics the queue's metrics, whose queue gauge is the backlog
	 * @param holding where the messages wait
	 * @param threshold the backlog at which the memory alarm blocks publishers; empty
	 * without one
	 */
	Backlog(Simulation simulation, NodeMetrics metrics, Holding holding, OptionalDouble threshold) {
		this.simulation = simulation;
		this.metrics = metrics;
		this.holding = holding;
		this.threshold = threshold;
	}

	/**
	 * Adds a message for a consumer to take, which may raise the memory alarm.
	 * @param message the message
	 * @param now the time
	 */
	void add(Message message, double now) {
		this.depth++;
		this.metrics.queue().add(now, 1);
		this.holding.add(message, now);
		if (this.threshold.isPresent() && !this.blocked && this.depth >= this.threshold.getAsDouble()) {
			this.blocked = true;
			this.unblocked = this.simulation.event();
			this.metrics.count(MEMORY_ALARMS, now);
		}
	}

	/**
	 * Notes a message a consumer took, which may lift the memory alarm.
	 * @param now the time
	 */
	void taken(double now) {
		this.depth--;
		this.metrics.queue().add(now, -1);
		if (this.blocked && this.threshold.isPresent() && this.depth < ALARM_CLEARS_AT * this.threshold.getAsDouble()) {
			this.blocked = false;
			Signal lifted = this.unblocked;
			if (lifted != null && !lifted.triggered()) {
				lifted.succeed();
			}
		}
	}

	/**
	 * The messages waiting for a consumer.
	 * @return the backlog
	 */
	int depth() {
		return this.depth;
	}

	/**
	 * Whether the memory alarm blocks publishers now.
	 * @return {@code true} while it does
	 */
	boolean blocked() {
		return this.blocked;
	}

	/**
	 * Waits until the memory alarm lets publishers through, however often it is raised
	 * again meanwhile.
	 * @param next what to do once it does
	 */
	void whenUnblocked(Runnable next) {
		Signal lifted = this.unblocked;
		if (!this.blocked || lifted == null) {
			next.run();
			return;
		}
		lifted.then(() -> whenUnblocked(next));
	}

}
