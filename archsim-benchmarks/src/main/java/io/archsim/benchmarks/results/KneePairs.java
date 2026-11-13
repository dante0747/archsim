package io.archsim.benchmarks.results;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;
import java.util.stream.Stream;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;

/**
 * Each kept calibration's re-simulated knee on both engines: the frozen engine's
 * development checks against the Java engine's, paired by the check's file over both
 * folders, with whether each engine checked it, whether the two derived the same inputs,
 * and whether the knees are within 2% of each other. A calibration only one engine
 * checked misses.
 */
public final class KneePairs {

	/**
	 * The frozen engine the Java engine is checked against, which predicted every latest
	 * run.
	 */
	public static final String FROZEN_ENGINE = "0.16.0";

	/**
	 * The frozen engine's checks of every kept run, from the harness the Java engine's
	 * came from.
	 */
	public static final String PYTHON_KNEES = FROZEN_ENGINE + "-port";

	/**
	 * The Java engine's checks, written before it had a version of its own: the record.
	 */
	public static final String JAVA_KNEES = FROZEN_ENGINE + "-java";

	/**
	 * How far a Java figure may be from the Python one, as a share of it: the plan's 2%.
	 */
	private static final double WITHIN = 0.02;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final String JSON = ".json";

	private KneePairs() {
	}

	/**
	 * The pairs under the development checks' folder.
	 * @param dev {@code benchmarks/results/dev/}
	 * @return a row per calibration either engine checked, in the order of their paths:
	 * {@code experiment}, {@code calibration}, the knees {@code python} and {@code java}
	 * (None where a check found none or is not there), {@code checked} and
	 * {@code resimulated} (whether each engine checked it), {@code sameInputs} (None
	 * unless both did) and {@code withinTwoPercent}
	 */
	public static List<ObjectNode> of(Path dev) {
		Path python = dev.resolve(PYTHON_KNEES);
		Path java = dev.resolve(JAVA_KNEES);
		TreeSet<String> names = new TreeSet<>(KneePairs::byParts);
		names.addAll(checks(python));
		names.addAll(checks(java));
		List<ObjectNode> rows = new ArrayList<>();
		for (String name : names) {
			rows.add(row(name, PythonValues.orNone(ResultFiles.read(python.resolve(name))),
					PythonValues.orNone(ResultFiles.read(java.resolve(name)))));
		}
		return rows;
	}

	/**
	 * Whether both engines re-simulated a calibration and found the same knee, or both
	 * none: the rule the parity page and the validation page's sentence count by.
	 * @param pair a pair ({@link #of})
	 * @return whether they found the same knee
	 */
	public static boolean sameKnee(JsonNode pair) {
		return pair.get("checked").booleanValue() && pair.get("resimulated").booleanValue()
				&& PythonValues.equal(pair.get("java"), pair.get("python"));
	}

	// a calibration's knees on both engines; a check not there is None
	private static ObjectNode row(String name, JsonNode python, JsonNode java) {
		boolean both = !PythonValues.isNone(python) && !PythonValues.isNone(java);
		JsonNode pythonKnee = knee(python);
		JsonNode javaKnee = knee(java);
		int slash = name.lastIndexOf('/');
		ObjectNode row = NODES.objectNode()
			.put("experiment", (slash < 0) ? "." : name.substring(0, slash))
			.put("calibration", name.substring(slash + 1, name.length() - JSON.length()));
		row.set("python", pythonKnee);
		row.set("java", javaKnee);
		row.put("checked", !PythonValues.isNone(python)).put("resimulated", !PythonValues.isNone(java));
		row.set("sameInputs", both ? sameInputs(python, java) : PythonValues.none());
		return row.put("withinTwoPercent", both && within(pythonKnee, javaKnee));
	}

	// whether both engines derived the same inputs for the calibration
	private static JsonNode sameInputs(JsonNode python, JsonNode java) {
		return BooleanNode
			.valueOf(PythonValues.equal(PythonValues.get(python, "inputs"), PythonValues.get(java, "inputs")));
	}

	// Python's order of paths: part by part, so pg/ comes before pg-mixed/
	private static int byParts(String left, String right) {
		return Arrays.compare(left.split("/"), right.split("/"));
	}

	// a check's knee as a float, or None
	private static JsonNode knee(JsonNode check) {
		if (PythonValues.isNone(check)) {
			return PythonValues.none();
		}
		JsonNode knee = PythonValues.get(check.get("comparison").get("predictedKnee"), "kneeRps");
		return PythonValues.isNone(knee) ? knee : PythonValues.number(knee.doubleValue());
	}

	// two knees within 2%, or two checks that found none
	private static boolean within(JsonNode python, JsonNode java) {
		if (PythonValues.isNone(python) || PythonValues.isNone(java)) {
			return PythonValues.isNone(python) && PythonValues.isNone(java);
		}
		return Math.abs(java.doubleValue() - python.doubleValue()) <= WITHIN * Math.abs(python.doubleValue());
	}

	// the checks under a folder, by their path in it
	private static List<String> checks(Path folder) {
		if (!Files.isDirectory(folder)) {
			return List.of();
		}
		try (Stream<Path> files = Files.walk(folder)) {
			return files.filter((file) -> file.getFileName().toString().endsWith(JSON))
				.map((file) -> folder.relativize(file).toString().replace('\\', '/'))
				.toList();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
