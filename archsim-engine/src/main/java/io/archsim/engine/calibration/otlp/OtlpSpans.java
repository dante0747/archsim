package io.archsim.engine.calibration.otlp;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.BooleanNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.model.text.PythonText;

/**
 * The spans of an OTLP JSON export (as the collector's file exporter or {@code otel-cli}
 * write it), read as the Python engine's {@code parse_otlp} reads them. A missing key is
 * an {@link IllegalArgumentException} with the key's {@code repr()} (Python's
 * {@code KeyError}), a time that is no whole number one in Python's words (its
 * {@code ValueError}), and what Python failed on with an {@code AttributeError} or a
 * {@code TypeError} an {@link OtlpExportException}. An id, a name or a kind that is not
 * text is read as the text Python's {@code str()} gives it.
 */
public final class OtlpSpans {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/** The kinds by the numbers OTLP writes them as. */
	private static final Map<Long, String> KINDS = Map.of(1L, "INTERNAL", 2L, "SERVER", 3L, "CLIENT", 4L, "PRODUCER",
			5L, "CONSUMER");

	private static final String INTERNAL = "INTERNAL";

	private static final String KIND_PREFIX = "SPAN_KIND_";

	private static final List<String> VALUE_KEYS = List.of("stringValue", "intValue", "doubleValue", "boolValue");

	private OtlpSpans() {
	}

	/**
	 * Every span of an export, service by service.
	 * @param data the export
	 * @return the spans, in the export's order
	 * @throws IllegalArgumentException in Python's words for a span without an id or a
	 * time, or a time that is no whole number or beyond what the engine reads
	 * @throws OtlpExportException for data that is not an export
	 */
	public static List<Span> parse(JsonNode data) {
		List<Span> spans = new ArrayList<>();
		for (JsonNode resource : iterated(lookedUp(data).path("resourceSpans"))) {
			JsonNode attributes = lookedUp(truthyOr(lookedUp(resource).path("resource"))).path("attributes");
			JsonNode name = attributes(attributes).path("service.name");
			String service = name.isMissingNode() ? "unknown" : PythonText.str(name);
			JsonNode scopes = PythonText.truthy(resource.path("scopeSpans")) ? resource.path("scopeSpans")
					: resource.path("instrumentationLibrarySpans");
			for (JsonNode scope : iterated(scopes)) {
				iterated(lookedUp(scope).path("spans")).forEach((span) -> spans.add(span(lookedUp(span), service)));
			}
		}
		return spans;
	}

	private static Span span(JsonNode span, String service) {
		JsonNode parent = span.path("parentSpanId");
		JsonNode name = span.path("name");
		return new Span(text(required(span, "traceId")), text(required(span, "spanId")),
				PythonText.truthy(parent) ? text(parent) : null, service, name.isMissingNode() ? "" : text(name),
				kind(span.path("kind")), nanoseconds(required(span, "startTimeUnixNano")),
				nanoseconds(required(span, "endTimeUnixNano")), attributes(span.path("attributes")));
	}

	/**
	 * A span's kind by its name: from OTLP's number, or its name without its
	 * {@code SPAN_KIND_} prefix.
	 * @param kind the kind as written; missing, or {@code true} (Python's 1), for
	 * {@code INTERNAL}
	 * @return the kind's name
	 */
	static String kind(JsonNode kind) {
		if (kind.isMissingNode() || BooleanNode.TRUE.equals(kind)) {
			return INTERNAL;
		}
		if (kind.isIntegralNumber() && kind.canConvertToLong() && KINDS.containsKey(kind.longValue())) {
			return KINDS.get(kind.longValue());
		}
		return withoutPrefix(text(kind));
	}

	/**
	 * Attributes by key, each value the first of its typed values OTLP writes.
	 * @param items the attributes; nothing when not truthy
	 * @return each value by its key, {@code null} for one of no known type
	 * @throws IllegalArgumentException as Python's {@code KeyError} for an attribute
	 * without a key
	 * @throws OtlpExportException as Python's {@code TypeError} for attributes that are
	 * not a list of objects, a key that is a list or an object, or a value Python cannot
	 * look its types up in
	 */
	static ObjectNode attributes(JsonNode items) {
		ObjectNode attributes = NODES.objectNode();
		for (JsonNode item : iterated(items)) {
			if (!item.isObject()) {
				throw OtlpExportException.typeError();
			}
			JsonNode key = required(item, "key");
			JsonNode value = value(item.path("value"));
			if (key.isContainer()) {
				throw OtlpExportException.typeError();
			}
			attributes.set(text(key), value);
		}
		return attributes;
	}

	/**
	 * An attribute's value, as {@code _attr_value(a.get("value") or {})} finds it.
	 * @param value the attribute's {@code value}
	 * @return its first typed value; null for none
	 * @throws OtlpExportException as Python's {@code TypeError} for a number, or for text
	 * or a list holding a typed value's name, which Python then indexes by it
	 */
	private static JsonNode value(JsonNode value) {
		if (!PythonText.truthy(value)) {
			return NODES.nullNode();
		}
		if (value.isNumber() || value.isBoolean()) {
			throw OtlpExportException.typeError();
		}
		for (String key : VALUE_KEYS) {
			if (value.isObject() && value.has(key)) {
				return value.get(key).deepCopy();
			}
			if (contains(value, key)) {
				throw OtlpExportException.typeError();
			}
		}
		return NODES.nullNode();
	}

	/**
	 * Whether Python's {@code key in value} holds for text or a list.
	 * @param value text or a list
	 * @param key the key
	 * @return whether the text holds the key, or the list has it as an item
	 */
	private static boolean contains(JsonNode value, String key) {
		if (value.isString()) {
			return value.stringValue().contains(key);
		}
		if (value.isArray()) {
			for (JsonNode item : value) {
				if (item.isString() && item.stringValue().equals(key)) {
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * A time in nanoseconds, as Python's {@code int()} reads it.
	 * @param value the time: text, a number or a boolean
	 * @return the nanoseconds
	 * @throws IllegalArgumentException in Python's words when it is no whole number, and
	 * when it is beyond a long's range, which Python read
	 * @throws OtlpExportException as Python's {@code TypeError} for a time of another
	 * kind
	 */
	static long nanoseconds(JsonNode value) {
		BigInteger number;
		try {
			number = switch (value.getNodeType()) {
				case STRING -> PythonNumbers.parseInt(value.stringValue());
				case BOOLEAN -> value.booleanValue() ? BigInteger.ONE : BigInteger.ZERO;
				case NUMBER ->
					value.isIntegralNumber() ? value.bigIntegerValue() : PythonNumbers.truncate(value.doubleValue());
				default -> throw OtlpExportException.typeError();
			};
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException(String.valueOf(ex.getMessage()), ex);
		}
		if (number.bitLength() >= Long.SIZE) {
			throw new IllegalArgumentException("a span time is beyond what the engine reads (the years 1677 to 2262)");
		}
		return number.longValue();
	}

	private static JsonNode required(JsonNode object, String key) {
		JsonNode value = object.path(key);
		if (value.isMissingNode()) {
			throw new IllegalArgumentException(PythonText.repr(key));
		}
		return value;
	}

	/**
	 * Something Python looks keys up in with {@code .get}.
	 * @param value the value
	 * @return the value, an object
	 * @throws OtlpExportException as Python's {@code AttributeError} when it is not an
	 * object
	 */
	private static JsonNode lookedUp(JsonNode value) {
		if (!value.isObject()) {
			throw OtlpExportException.attributeError();
		}
		return value;
	}

	/**
	 * Python's {@code value or {}}.
	 * @param value the value
	 * @return the value; an empty object when it is not truthy
	 */
	private static JsonNode truthyOr(JsonNode value) {
		return PythonText.truthy(value) ? value : NODES.objectNode();
	}

	/**
	 * What Python's {@code for item in value or []} goes through.
	 * @param value the value
	 * @return nothing when it is not truthy; a list's items, an object's keys or text's
	 * characters, each as text
	 * @throws OtlpExportException as Python's {@code TypeError} for a number or a boolean
	 */
	private static List<JsonNode> iterated(JsonNode value) {
		List<JsonNode> items = new ArrayList<>();
		if (!PythonText.truthy(value)) {
			return items;
		}
		if (value.isArray()) {
			value.forEach(items::add);
		}
		else if (value.isObject()) {
			value.propertyNames().forEach((key) -> items.add(NODES.stringNode(key)));
		}
		else if (value.isString()) {
			value.stringValue().codePoints().forEach((point) -> items.add(NODES.stringNode(Character.toString(point))));
		}
		else {
			throw OtlpExportException.typeError();
		}
		return items;
	}

	private static String text(JsonNode value) {
		return value.isString() ? value.stringValue() : PythonText.str(value);
	}

	private static String withoutPrefix(String kind) {
		return kind.startsWith(KIND_PREFIX) ? kind.substring(KIND_PREFIX.length()) : kind;
	}

}
