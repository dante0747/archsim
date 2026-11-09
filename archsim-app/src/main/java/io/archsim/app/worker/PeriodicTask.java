package io.archsim.app.worker;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import org.springframework.scheduling.support.TaskUtils;

/**
 * A task run again and again on a platform thread of its own, a pause after each run,
 * until it is stopped. A run that throws is logged and the next runs as planned: an
 * executor would otherwise drop the task at its first exception, and keep the exception
 * where no one looks.
 */
final class PeriodicTask {

	private final ScheduledExecutorService executor;

	private final ScheduledFuture<?> schedule;

	private PeriodicTask(ScheduledExecutorService executor, ScheduledFuture<?> schedule) {
		this.executor = executor;
		this.schedule = schedule;
	}

	/**
	 * Starts running a task, the first time after one pause.
	 * @param threadName the name of its thread, a daemon
	 * @param pause the time from the end of one run to the start of the next
	 * @param task the task
	 * @return the running task
	 */
	static PeriodicTask start(String threadName, Duration pause, Runnable task) {
		ScheduledExecutorService executor = Executors
			.newSingleThreadScheduledExecutor(Thread.ofPlatform().name(threadName).daemon(true).factory());
		Runnable logged = TaskUtils.decorateTaskWithErrorHandler(task, TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER, true);
		long nanos = pause.toNanos();
		return new PeriodicTask(executor, executor.scheduleWithFixedDelay(logged, nanos, nanos, TimeUnit.NANOSECONDS));
	}

	/**
	 * Stops the task, waiting for a run under way to end.
	 * @param wait the longest wait
	 */
	void stop(Duration wait) {
		this.schedule.cancel(false);
		this.executor.shutdown();
		try {
			this.executor.awaitTermination(wait.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
