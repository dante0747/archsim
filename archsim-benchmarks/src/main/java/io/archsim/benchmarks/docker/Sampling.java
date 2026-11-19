package io.archsim.benchmarks.docker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalDouble;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.archsim.benchmarks.experiments.Protocol;

/**
 * A {@link ResourceSampler}'s reading: it waits for the load to start, then reads every
 * container at once every five seconds through the window, on a pool of its own.
 */
final class Sampling {

	private final Instruments instruments;

	private final ResourceSampler.Window window;

	private final Map<String, List<CgroupReading>> readings = new LinkedHashMap<>();

	private final CountDownLatch stopped = new CountDownLatch(1);

	Sampling(Instruments instruments, List<String> services, ResourceSampler.Window window) {
		this.instruments = instruments;
		this.window = window;
		services.forEach((service) -> this.readings.put(service, Collections.synchronizedList(new ArrayList<>())));
	}

	/**
	 * Each container's readings so far, in the stack's order.
	 * @return the readings
	 */
	Map<String, List<CgroupReading>> readings() {
		return this.readings;
	}

	/**
	 * Stops the reading at its next wait.
	 */
	void stop() {
		this.stopped.countDown();
	}

	/**
	 * Reads the containers through the window.
	 */
	void run() {
		Map<String, String> containers = new LinkedHashMap<>();
		this.readings.keySet()
			.forEach((service) -> this.instruments.compose()
				.container(service)
				.ifPresent((container) -> containers.put(service, container)));
		OptionalDouble start = LoadStart.await(this.instruments, this.stopped);
		if (start.isEmpty() || containers.isEmpty()) {
			return;
		}
		try (ExecutorService readers = Executors.newFixedThreadPool(containers.size())) {
			double moment = start.getAsDouble() + this.window.warmup();
			double end = moment + this.window.duration() - 1;
			Ticker ticker = this.instruments.ticker();
			while (!ticker.awaitStop(this.stopped, Math.max(0, moment - ticker.now()))) {
				readEach(containers, readers);
				if (moment >= end) {
					break;
				}
				moment = Math.min(moment + Protocol.SAMPLE_S, end);
			}
		}
	}

	// every container read at once, each reading kept under its service
	private void readEach(Map<String, String> containers, ExecutorService readers) {
		Map<String, Future<CgroupReading>> read = new LinkedHashMap<>();
		containers.forEach((service, container) -> read.put(service,
				readers.submit(() -> this.instruments.cgroups().read(container))));
		read.forEach((service, reading) -> Objects.requireNonNull(this.readings.get(service)).add(result(reading)));
	}

	private static CgroupReading result(Future<CgroupReading> reading) {
		try {
			return reading.get();
		}
		catch (ExecutionException ex) {
			Throwable cause = Objects.requireNonNullElse(ex.getCause(), ex);
			String why = Objects.requireNonNullElse(cause.getMessage(), cause.toString());
			throw new IllegalStateException("a container's counters could not be read: " + why, cause);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while reading the containers", ex);
		}
	}

}
