package io.archsim.mcp;

import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.engine.text.JsonDecodeException;
import io.archsim.engine.text.JsonTooDeepException;
import io.archsim.engine.text.PythonJsonReader;

/**
 * A tool's arguments, as the SDK checked them against the tool's input schema, read as
 * the Python server read them: a document given as an object or as the JSON text of one.
 */
final class Arguments {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final JsonNode values;

	private Arguments(JsonNode values) {
		this.values = values;
	}

	/**
	 * The arguments of a call.
	 * @param arguments the call's arguments, by name; {@code null} for none
	 * @return the arguments
	 */
	static Arguments of(@Nullable Map<String, Object> arguments) {
		return new Arguments((arguments != null) ? JSON.valueToTree(arguments) : JSON.createObjectNode());
	}

	/**
	 * The {@code document} argument, as the Python server's {@code _doc} read it.
	 * @return the document
	 * @throws ToolRefusal when it is text that is not JSON, or not an object
	 */
	JsonNode document() {
		JsonNode document = this.values.path("document");
		if (document.isString()) {
			try {
				document = PythonJsonReader.read(document.stringValue());
			}
			catch (JsonDecodeException | JsonTooDeepException ex) {
				throw new ToolRefusal("document is not valid JSON: " + ex.getMessage(), ex);
			}
		}
		if (!document.isObject()) {
			throw new ToolRefusal("document must be an ArchSim architecture document (a JSON object)");
		}
		return document;
	}

	/**
	 * A text argument.
	 * @param name its name
	 * @return its value; {@code null} when not given, or given as {@code null}
	 */
	@Nullable String text(String name) {
		JsonNode value = this.values.path(name);
		return value.isString() ? value.stringValue() : null;
	}

	/**
	 * A text argument the tool's input schema requires, which the SDK has checked is
	 * there.
	 * @param name its name
	 * @return its value
	 * @throws IllegalStateException when it is not there: the schema does not require it
	 */
	String required(String name) {
		JsonNode value = this.values.path(name);
		if (!value.isString()) {
			throw new IllegalStateException("the input schema does not require " + name);
		}
		return value.stringValue();
	}

	/**
	 * A number argument.
	 * @param name its name
	 * @return its value; {@code null} when not given, or given as {@code null}
	 */
	@Nullable Double number(String name) {
		JsonNode value = this.values.path(name);
		return value.isNumber() ? value.doubleValue() : null;
	}

	/**
	 * A whole-number argument.
	 * @param name its name
	 * @param otherwise its value when not given
	 * @return its value
	 */
	int integer(String name, int otherwise) {
		JsonNode value = this.values.path(name);
		return value.isNumber() ? value.intValue() : otherwise;
	}

}
