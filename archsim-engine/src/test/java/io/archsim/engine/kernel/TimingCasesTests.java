package io.archsim.engine.kernel;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.archsim.engine.kernel.KernelCases.Line;
import io.archsim.engine.kernel.KernelCases.Run;
import io.archsim.engine.kernel.KernelCases.Trace;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Timeouts, routines and conditions in SimPy's order: each test runs the Java twin of a
 * program of the since-deleted {@code scripts/export_kernel_cases.py} (named after it)
 * and expects the lines SimPy recorded and as many events processed. Each step of a
 * program is a method, named after what the Python did there.
 */
class TimingCasesTests {

	private final Simulation simulation = new Simulation();

	private final Trace trace = new Trace(this.simulation);

	@Test
	void run_timeoutsAndStarts_keepsSimPysOrder() {
		this.simulation.timeout(0);
		this.simulation.start((finish) -> sleep("a", List.of(0.1, 0.2, 0.0), finish));
		this.simulation.start((finish) -> sleep("b", List.of(0.3, 0.0), finish));
		this.simulation.start((finish) -> sleep("c", List.of(0.30000000000000004, 0.1), finish));

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("timeoutsAndStarts"));
	}

	@Test
	void run_nestedWaits_keepsSimPysOrder() {
		this.simulation.start(this::waitForTheEarlyEvent);

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("nestedWaits"));
	}

	@Test
	void run_conditions_keepsSimPysOrder() {
		this.simulation.start((finish) -> this.simulation.allOf(List.of()).then(() -> allOfChildren(finish)));

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("conditions"));
	}

	@Test
	void run_nestedConditions_stopsTheInnerConditionWithTheOuter() {
		List<Timeout> waited = List.of(this.simulation.timeout(2), this.simulation.timeout(3));
		Timeout early = this.simulation.timeout(1);
		Condition inner = this.simulation.allOf(waited);
		this.simulation.start((finish) -> inner.then(() -> {
			this.trace.log("inner waiter resumed");
			finish.run();
		}));
		this.simulation.start((finish) -> outerAnyOf(inner, waited, early, finish));

		Run run = run();

		assertThat(run).isEqualTo(KernelCases.recorded("nestedConditions"));
	}

	@Test
	void runUntil_chunks_stopsAndCountsAsSimPy() {
		this.simulation.start((finish) -> tick("fast", 0.3));
		this.simulation.start((finish) -> tick("slow", 1.1));

		List<Long> counts = runInChunks(3.0, 4, 3);

		assertThat(new Chunks(this.trace.lines(), counts))
			.isEqualTo(new Chunks(KernelCases.trace("chunks"), KernelCases.chunkCounts()));
	}

	private void sleep(String name, List<Double> delays, Runnable finish) {
		this.trace.log(name + " starts");
		sleepFrom(name, delays, 0, finish);
	}

	private void sleepFrom(String name, List<Double> delays, int index, Runnable finish) {
		if (index == delays.size()) {
			finish.run();
			return;
		}
		double delay = delays.get(index);
		this.simulation.timeout(delay).then(() -> {
			this.trace.log(name + " woke after " + delay);
			sleepFrom(name, delays, index + 1, finish);
		});
	}

	private void waitForTheEarlyEvent(Runnable finish) {
		Promise<String> early = this.simulation.promise();
		early.succeed("early");
		this.trace.log("parent waits for the early event");
		this.simulation.timeout(1).then(() -> early.thenAccept((value) -> {
			this.trace.log("parent got " + value + " without waiting");
			Result result = new Result();
			Routine first = this.simulation.start((done) -> child("first", 0.5, result, done));
			first.then(() -> waitForTheSecondChild(result.value, finish));
		}));
	}

	private void waitForTheSecondChild(String firstResult, Runnable finish) {
		this.trace.log("parent got " + firstResult);
		Result result = new Result();
		Routine second = this.simulation.start((done) -> child("second", 0, result, done));
		this.simulation.timeout(0).then(() -> {
			this.trace.log("parent past a zero timeout");
			second.then(() -> {
				this.trace.log("parent got " + result.value);
				finish.run();
			});
		});
	}

	/**
	 * A child routine that returns its name: Python's process value, set by the step that
	 * finishes the routine.
	 */
	private void child(String name, double delay, Result result, Runnable finish) {
		this.simulation.timeout(delay).then(() -> {
			this.trace.log(name + " done");
			result.value = name;
			finish.run();
		});
	}

	private void allOfChildren(Runnable finish) {
		this.trace.log("empty all-of passed");
		List<Routine> children = new ArrayList<>();
		List<Double> delays = List.of(0.5, 0.0, 0.25);
		for (int index = 0; index < delays.size(); index++) {
			String name = "c" + index;
			double delay = delays.get(index);
			children.add(this.simulation.start((done) -> child(name, delay, new Result(), done)));
		}
		this.simulation.allOf(children).then(() -> anyOfTwoTimeouts(finish));
	}

	private void anyOfTwoTimeouts(Runnable finish) {
		this.trace.log("all children done");
		Timeout first = this.simulation.timeout(1);
		Timeout second = this.simulation.timeout(1);
		this.simulation.anyOf(List.of(first, second)).then(() -> {
			this.trace.log("any-of fired, a=" + KernelCases.pythonBoolean(first.processed()) + " b="
					+ KernelCases.pythonBoolean(second.processed()));
			Signal done = this.simulation.event();
			this.simulation.timeout(0).then(() -> anyOfAProcessedEvent(done, finish));
		});
	}

	private void anyOfAProcessedEvent(Signal done, Runnable finish) {
		done.succeed();
		this.simulation.timeout(0)
			.then(() -> this.simulation.anyOf(List.of(done, this.simulation.timeout(3)))
				.then(() -> lateTimeout(finish)));
	}

	private void lateTimeout(Runnable finish) {
		this.trace.log("any-of of a processed event passed");
		Timeout late = this.simulation.timeout(2);
		this.simulation.anyOf(List.of(this.simulation.timeout(1), late)).then(() -> {
			this.trace.log("any-of fired before the late timeout, late=" + KernelCases.pythonBoolean(late.processed()));
			late.then(() -> {
				this.trace.log("late timeout seen");
				finish.run();
			});
		});
	}

	private void outerAnyOf(Condition inner, List<Timeout> waited, Timeout early, Runnable finish) {
		this.simulation.anyOf(List.of(inner, early)).then(() -> {
			this.trace.log("outer fired, inner triggered=" + KernelCases.pythonBoolean(inner.triggered()));
			this.simulation.timeout(5).then(() -> {
				this.trace.log("later, inner triggered=" + KernelCases.pythonBoolean(inner.triggered()) + " a="
						+ KernelCases.pythonBoolean(waited.get(0).processed()) + " b="
						+ KernelCases.pythonBoolean(waited.get(1).processed()));
				finish.run();
			});
		});
	}

	private void tick(String name, double every) {
		this.simulation.timeout(every).then(() -> {
			this.trace.log(name + " tick");
			tick(name, every);
		});
	}

	/**
	 * The Python engine's chunked run: to {@code end} in {@code parts} chunks, each
	 * checking every {@code every} events.
	 */
	private List<Long> runInChunks(double end, int parts, int every) {
		List<Long> counts = new ArrayList<>();
		double step = end / parts;
		double until = 0;
		while (until < end) {
			until = Math.min(until + step, end);
			if (until > this.simulation.now()) {
				counts.add(this.simulation.runUntil(until, every, () -> this.trace.log("check")));
			}
			this.trace.log("chunk to " + until);
		}
		return counts;
	}

	private Run run() {
		long events = KernelCases.runAll(this.simulation);
		return new Run(this.trace.lines(), events);
	}

	/**
	 * A child routine's result, set by the step that finishes it and read by its waiter.
	 */
	private static final class Result {

		private String value = "";

	}

	/**
	 * What the chunked run saw and how many events each chunk counted.
	 *
	 * @param trace the lines
	 * @param counts the counts
	 */
	record Chunks(List<Line> trace, List<Long> counts) {

	}

}
