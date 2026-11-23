package io.archsim.engine.parity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

/**
 * Where the Java's import report words what the autoscaler reads as the frozen engine did
 * not: Python said ArchSim scales on CPU, but it scales on worker utilization too
 * (archsim-engine's README). A recorded report is read as the Java words it, so every
 * word of it is still compared exactly, the departed ones in the Java's words.
 */
public final class ImportWordingDepartures {

	private static final String PYTHON_DEFAULT_TARGET = " scales on CPU at ArchSim's default target";

	private static final String JAVA_DEFAULT_TARGET = " scales at ArchSim's default target";

	private static final String PYTHON_IGNORED_METRIC = " (ArchSim scales on CPU utilization)";

	private static final String JAVA_IGNORED_METRIC = " (ArchSim scales on worker utilization "
			+ "or, with a CPU size, CPU utilization if higher)";

	private ImportWordingDepartures() {
	}

	/**
	 * An import of the frozen engine's as the Java words it.
	 * @param recorded Python's design and report, which are not changed
	 * @return a copy, its report's guessed and ignored texts in the Java's words
	 */
	public static JsonNode asJavaWordsIt(JsonNode recorded) {
		JsonNode copy = recorded.deepCopy();
		reword(copy.at("/report/guessed"), PYTHON_DEFAULT_TARGET, JAVA_DEFAULT_TARGET);
		reword(copy.at("/report/ignored"), PYTHON_IGNORED_METRIC, JAVA_IGNORED_METRIC);
		return copy;
	}

	// a text's ending in Python's words, in the Java's
	private static void reword(JsonNode texts, String python, String java) {
		if (!(texts instanceof ArrayNode array)) {
			return;
		}
		for (int index = 0; index < array.size(); index++) {
			String text = array.get(index).stringValue();
			if (text.endsWith(python)) {
				array.set(index, text.substring(0, text.length() - python.length()) + java);
			}
		}
	}

}
