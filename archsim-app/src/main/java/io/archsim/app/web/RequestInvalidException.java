package io.archsim.app.web;

import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingError;

/**
 * A request that is not what the endpoint takes, refused as FastAPI refused it:
 * {@code 422 {"detail": [...]}}, one pydantic error per thing wrong, each located under
 * {@code "body"} or {@code "query"}.
 */
public final class RequestInvalidException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The error type pydantic gives a value that is not there. */
	private static final String MISSING = "missing";

	private final transient ArrayNode detail;

	private RequestInvalidException(ArrayNode detail) {
		super("Request body invalid: " + detail.size() + " errors");
		this.detail = detail;
	}

	/**
	 * A request without the body the endpoint needs.
	 * @return the refusal
	 */
	static RequestInvalidException missingBody() {
		return new RequestInvalidException(NODES.arrayNode()
			.add(error(MISSING, NODES.arrayNode().add("body"), "Field required").putNull("input")));
	}

	/**
	 * A body that is not JSON.
	 * @param offset where in the body reading stopped, in characters
	 * @param reason what the JSON reader says is wrong
	 * @return the refusal
	 */
	static RequestInvalidException notJson(long offset, String reason) {
		ObjectNode error = error("json_invalid", NODES.arrayNode().add("body").add(offset), "JSON decode error");
		error.set("input", NODES.objectNode());
		error.putObject("ctx").put("error", reason);
		return new RequestInvalidException(NODES.arrayNode().add(error));
	}

	/**
	 * A query parameter longer than the endpoint takes.
	 * @param name the parameter's name
	 * @param value what the request gave
	 * @param maxLength the most characters it may have
	 * @return the refusal, as pydantic words it
	 */
	public static RequestInvalidException queryTooLong(String name, String value, int maxLength) {
		ObjectNode error = error("string_too_long", NODES.arrayNode().add("query").add(name),
				"String should have at most %d characters".formatted(maxLength));
		error.put("input", value);
		error.putObject("ctx").put("max_length", maxLength);
		return new RequestInvalidException(NODES.arrayNode().add(error));
	}

	/**
	 * Query parameters an endpoint needs and the request lacks.
	 * @param names the parameters' names
	 * @return the refusal, one error each, as pydantic words it
	 */
	public static RequestInvalidException queryMissing(List<String> names) {
		ArrayNode errors = NODES.arrayNode();
		names.forEach((name) -> errors
			.add(error(MISSING, NODES.arrayNode().add("query").add(name), "Field required").putNull("input")));
		return new RequestInvalidException(errors);
	}

	/**
	 * A query parameter that should be a boolean and is not one of pydantic's words for
	 * one.
	 * @param name the parameter's name
	 * @param value what the request gave
	 * @return the refusal, as pydantic words it
	 */
	public static RequestInvalidException queryNotBoolean(String name, String value) {
		ObjectNode error = error("bool_parsing", NODES.arrayNode().add("query").add(name),
				"Input should be a valid boolean, unable to interpret input");
		error.put("input", value);
		return new RequestInvalidException(NODES.arrayNode().add(error));
	}

	/**
	 * A query parameter that should be a whole number and is not one.
	 * @param name the parameter's name
	 * @param value what the request gave
	 * @return the refusal, as pydantic words it
	 */
	public static RequestInvalidException queryNotInteger(String name, String value) {
		ObjectNode error = error("int_parsing", NODES.arrayNode().add("query").add(name),
				"Input should be a valid integer, unable to parse string as an integer");
		error.put("input", value);
		return new RequestInvalidException(NODES.arrayNode().add(error));
	}

	/**
	 * A whole-number query parameter below the least the endpoint takes.
	 * @param name the parameter's name
	 * @param value what the request gave
	 * @param minimum the least it may be
	 * @return the refusal, as pydantic words it
	 */
	public static RequestInvalidException queryBelow(String name, String value, long minimum) {
		ObjectNode error = error("greater_than_equal", NODES.arrayNode().add("query").add(name),
				"Input should be greater than or equal to " + minimum);
		error.put("input", value);
		error.putObject("ctx").put("ge", minimum);
		return new RequestInvalidException(NODES.arrayNode().add(error));
	}

	/**
	 * A whole-number query parameter above the most the endpoint takes.
	 * @param name the parameter's name
	 * @param value what the request gave
	 * @param maximum the most it may be
	 * @return the refusal, as pydantic words it
	 */
	public static RequestInvalidException queryAbove(String name, String value, long maximum) {
		ObjectNode error = error("less_than_equal", NODES.arrayNode().add("query").add(name),
				"Input should be less than or equal to " + maximum);
		error.put("input", value);
		error.putObject("ctx").put("le", maximum);
		return new RequestInvalidException(NODES.arrayNode().add(error));
	}

	/**
	 * A query parameter that is none of the words the endpoint takes.
	 * @param name the parameter's name
	 * @param value what the request gave
	 * @param choices the words it takes
	 * @return the refusal, as pydantic words it: {@code 'a', 'b' or 'c'}
	 */
	public static RequestInvalidException queryNotOneOf(String name, String value, List<String> choices) {
		List<String> quoted = choices.stream().map((choice) -> "'" + choice + "'").toList();
		String expected = (quoted.size() == 1) ? quoted.getFirst()
				: String.join(", ", quoted.subList(0, quoted.size() - 1)) + " or " + quoted.getLast();
		ObjectNode error = error("literal_error", NODES.arrayNode().add("query").add(name),
				"Input should be " + expected);
		error.put("input", value);
		error.putObject("ctx").put("expected", expected);
		return new RequestInvalidException(NODES.arrayNode().add(error));
	}

	/**
	 * A JSON body the codec could not read.
	 * @param errors what the codec found wrong, as pydantic reports it
	 * @param body the body, whose values the errors show as their {@code input}
	 * @return the refusal
	 */
	static RequestInvalidException of(List<BindingError> errors, JsonNode body) {
		ArrayNode detail = NODES.arrayNode();
		for (BindingError found : errors) {
			ArrayNode location = NODES.arrayNode().add("body");
			for (Object part : found.location().parts()) {
				if (part instanceof Integer index) {
					location.add(index);
				}
				else {
					location.add((String) part);
				}
			}
			ObjectNode error = error(found.type(), location, found.message());
			error.set("input", input(body, found));
			detail.add(error);
		}
		return new RequestInvalidException(detail);
	}

	/**
	 * FastAPI's {@code detail}: one error per thing wrong.
	 * @return a copy of the errors
	 */
	public ArrayNode detail() {
		return this.detail.deepCopy();
	}

	private static ObjectNode error(String type, ArrayNode location, String message) {
		ObjectNode error = NODES.objectNode();
		error.put("type", type);
		error.set("loc", location);
		error.put("msg", message);
		return error;
	}

	/**
	 * The value an error is about, as pydantic shows it: the value at its location, or
	 * for a missing value the object it is missing from.
	 * @param body the request's body
	 * @param error the error
	 * @return the value, or JSON {@code null} when there is none
	 */
	private static JsonNode input(JsonNode body, BindingError error) {
		List<Object> parts = error.location().parts();
		List<Object> path = MISSING.equals(error.type()) ? parts.subList(0, Math.max(0, parts.size() - 1)) : parts;
		JsonNode value = body;
		for (Object part : path) {
			value = (part instanceof Integer index) ? value.path(index) : value.path((String) part);
		}
		return value.isMissingNode() ? NODES.nullNode() : value;
	}

}
