package io.archsim.benchmarks.parity;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.results.KneePairs;

/**
 * The page's speed section: each template's median wall time on both engines, or a note
 * that speed is not measured yet. Speed is compared with {@code docs/performance.md} and
 * is not part of the bar; it waits for a quiet machine, since other work skews wall-clock
 * times.
 */
final class Speed {

	private final boolean measured;

	private final List<ObjectNode> rows;

	// whether both engines' speed was measured, and each template's row (SpeedRows)
	private Speed(boolean measured, List<ObjectNode> rows) {
		this.measured = measured;
		this.rows = List.copyOf(rows);
	}

	/**
	 * Speed not measured yet.
	 * @return the section
	 */
	static Speed notMeasured() {
		return new Speed(false, List.of());
	}

	/**
	 * Speed measured.
	 * @param rows each template's row
	 * @return the section
	 */
	static Speed of(List<ObjectNode> rows) {
		return new Speed(true, rows);
	}

	/**
	 * Whether any template was timed.
	 * @return whether there is a row
	 */
	boolean timed() {
		return !this.rows.isEmpty();
	}

	/**
	 * The section's lines.
	 * @return the lines
	 */
	List<String> lines() {
		if (!this.measured) {
			return List.of("Not yet measured. Speed is compared with [docs/performance.md](../performance.md) and is "
					+ "not part of the bar; it waits for a quiet machine, since other work skews wall-clock times. `"
					+ JavaParityPage.PYTHON_SPEED_COMMAND + "`, then `" + JavaParityPage.JAVA_SPEED_COMMAND
					+ "`, with no other heavy work running, write its inputs; this section then gives each "
					+ "template's median wall time on both engines.");
		}
		if (this.rows.isEmpty()) {
			return List.of("No template was timed.");
		}
		List<String> lines = new ArrayList<>(List.of("Each template as shipped (no scenarios): the median of " + runs()
				+ " on each engine, measured one engine after the other on the same machine with no "
				+ "other heavy work running; [docs/performance.md](../performance.md) says how much the runs spread. "
				+ "The Java runs follow a warm-up run in the same JVM and include StrictMath's cost: the simulator "
				+ "takes its logarithms, exponentials and powers from `StrictMath`, so it draws Python's random "
				+ "numbers on every platform ([ADR-0047](../adr/0047-draw-python-random-streams-in-java.md)).", "",
				"| Template | Requests | Python " + KneePairs.FROZEN_ENGINE + " | Java | Speed-up | Same result |",
				"| --- | --- | --- | --- | --- | --- |"));
		this.rows.forEach((row) -> lines
			.add("| " + row.get("template").stringValue() + " | " + ParityNumbers.count(row.get("requests").longValue())
					+ " | " + ParityNumbers.seconds(row.get("python")) + " | " + ParityNumbers.seconds(row.get("java"))
					+ " | " + ParityNumbers.times(row.get("speedUp")) + " | "
					+ ParityNumbers.yes(row.get("sameResult").booleanValue()) + " |"));
		return lines;
	}

	// "5 runs", or "each template's runs (3 to 5)" when they differ
	private String runs() {
		TreeSet<Integer> counts = new TreeSet<>();
		this.rows.forEach((row) -> row.get("runs")
			.valueStream()
			.map(JsonNode::intValue)
			.filter((count) -> count != 0)
			.forEach(counts::add));
		if (counts.size() == 1) {
			return counts.first() + " runs";
		}
		return "each template's runs (" + counts.first() + " to " + counts.last() + ")";
	}

}
