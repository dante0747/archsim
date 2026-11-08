package io.archsim.engine.kernel;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SimPy's traces of the small programs {@code scripts/export_kernel_cases.py} runs, read
 * from {@code kernel/cases.json}, and a recorder for the Java twins of those programs.
 */
final class KernelCases {

	private static final JsonNode CASES = read();

	private KernelCases() {
	}

	/**
	 * What SimPy recorded for a program, in order.
	 * @param program the program's name
	 * @return its lines
	 */
	static List<Line> trace(String program) {
		List<Line> lines = new ArrayList<>();
		for (JsonNode line : CASES.get(program).get("trace")) {
			lines.add(new Line(Double.parseDouble(line.get(0).stringValue()), line.get(1).stringValue()));
		}
		return lines;
	}

	/**
	 * How many events SimPy processed running a program to its end.
	 * @param program the program's name
	 * @return the count
	 */
	static long events(String program) {
		return CASES.get(program).get("events").asLong();
	}

	/**
	 * How many events each chunk of the chunked run counted.
	 * @return the counts, one per chunk
	 */
	static List<Long> chunkCounts() {
		List<Long> counts = new ArrayList<>();
		CASES.get("chunks").get("counts").forEach((count) -> counts.add(count.asLong()));
		return counts;
	}

	/**
	 * Runs a simulation to its end.
	 * @param simulation the simulation
	 * @return how many events it processed
	 */
	static long runAll(Simulation simulation) {
		long count = 0;
		while (simulation.step()) {
			count++;
		}
		return count;
	}

	/**
	 * A boolean as Python writes it.
	 * @param value the boolean
	 * @return {@code True} or {@code False}
	 */
	static String pythonBoolean(boolean value) {
		return value ? "True" : "False";
	}

	private static JsonNode read() {
		try (InputStream cases = Objects.requireNonNull(KernelCases.class.getResourceAsStream("cases.json"),
				"cases.json")) {
			return JsonMapper.builder().build().readTree(cases);
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read kernel/cases.json", ex);
		}
	}

	/**
	 * What SimPy recorded for a program and how many events it processed.
	 * @param program the program's name
	 * @return the run
	 */
	static Run recorded(String program) {
		return new Run(trace(program), events(program));
	}

	/**
	 * What a program saw and how many events it took.
	 *
	 * @param trace the lines
	 * @param events the events processed
	 */
	record Run(List<Line> trace, long events) {

	}

	/**
	 * One thing a program saw.
	 *
	 * @param time when, in simulated milliseconds
	 * @param what what it saw
	 */
	record Line(double time, String what) {

	}

	/**
	 * What a program saw, in order, with the simulated time it saw it.
	 */
	static final class Trace {

		private final Simulation simulation;

		private final List<Line> lines = new ArrayList<>();

		Trace(Simulation simulation) {
			this.simulation = simulation;
		}

		void log(String what) {
			this.lines.add(new Line(this.simulation.now(), what));
		}

		List<Line> lines() {
			return this.lines;
		}

	}

}
