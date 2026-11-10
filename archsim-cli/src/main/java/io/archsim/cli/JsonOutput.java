package io.archsim.cli;

import java.io.PrintWriter;

import tools.jackson.databind.JsonNode;

import io.archsim.model.text.PythonJson;

/**
 * JSON as the Python CLI wrote it, {@code json.dumps(value, indent=2)}: printed, or as
 * the text of a file.
 */
final class JsonOutput {

	/** The spaces each level of the JSON is indented by. */
	private static final int INDENT = 2;

	private JsonOutput() {
	}

	/**
	 * The text of a value, as {@code json.dumps(value, indent=2)} writes it.
	 * @param value the value
	 * @return its text, without a line end
	 */
	static String text(JsonNode value) {
		return PythonJson.writeIndented(value, INDENT);
	}

	/**
	 * Prints a value as {@code print(json.dumps(value, indent=2))} did, a line at a time
	 * so that every line ends as the console's do.
	 * @param out where to print it
	 * @param value the value
	 */
	static void print(PrintWriter out, JsonNode value) {
		text(value).lines().forEach(out::println);
	}

}
