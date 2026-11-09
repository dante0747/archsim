package io.archsim.app.worker;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The threads a worker's runs use: platform threads to simulate on, as many as it has
 * runs, never the app's virtual threads (ADR-0053), and one for their timers. They stop
 * with the role.
 */
@Component
@Profile("worker")
final class WorkerThreads implements DisposableBean {

	private final ExecutorService runs = Executors
		.newCachedThreadPool(Thread.ofPlatform().name("archsim-run-", 1).daemon(true).factory());

	private final ScheduledExecutorService timers = Executors
		.newSingleThreadScheduledExecutor(Thread.ofPlatform().name("archsim-run-timers").daemon(true).factory());

	/**
	 * Where runs simulate.
	 * @return the run threads
	 */
	Executor runs() {
		return this.runs;
	}

	/**
	 * Where the runs' deadlines and graces fire.
	 * @return the timer thread
	 */
	ScheduledExecutorService timers() {
		return this.timers;
	}

	@Override
	public void destroy() {
		this.runs.shutdownNow();
		this.timers.shutdownNow();
	}

}
