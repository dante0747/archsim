package io.archsim.app.worker;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The timers of one run: its hard deadline, and its cancel's grace, all called off once
 * the run has ended; none is set after that.
 */
final class RunTimers {

	private final ScheduledExecutorService timers;

	private final List<ScheduledFuture<?>> scheduled = new CopyOnWriteArrayList<>();

	private final AtomicBoolean calledOff = new AtomicBoolean();

	RunTimers(ScheduledExecutorService timers) {
		this.timers = timers;
	}

	/**
	 * Runs a task once a delay is over, unless the timers are called off first.
	 * @param delay the delay
	 * @param task the task
	 */
	void after(Duration delay, Runnable task) {
		if (this.calledOff.get()) {
			return;
		}
		ScheduledFuture<?> timer = this.timers.schedule(task, delay.toNanos(), TimeUnit.NANOSECONDS);
		this.scheduled.add(timer);
		// called off while it was being set
		if (this.calledOff.get()) {
			timer.cancel(false);
		}
	}

	/** Calls off every task not yet run, and every one set later. */
	void callOff() {
		this.calledOff.set(true);
		this.scheduled.forEach((timer) -> timer.cancel(false));
	}

}
