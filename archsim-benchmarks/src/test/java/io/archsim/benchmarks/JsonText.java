package io.archsim.benchmarks;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.text.PythonJsonReader;

/**
 * JSON written as text in a test, read as Python's {@code json} reads it, so that an
 * integer stays an integer.
 */
public final class JsonText {

	private JsonText() {
	}

	/**
	 * The value JSON text holds.
	 * @param text the text
	 * @return its value
	 */
	public static JsonNode read(String text) {
		return PythonJsonReader.read(text);
	}

	/**
	 * The object JSON text holds.
	 * @param text the text of an object
	 * @return the object
	 */
	public static ObjectNode object(String text) {
		return (ObjectNode) read(text);
	}

}
