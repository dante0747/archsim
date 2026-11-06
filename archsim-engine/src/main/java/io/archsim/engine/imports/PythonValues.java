package io.archsim.engine.imports;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.model.text.PythonText;

/**
 * Python's ways with the values YAML gives, where the importers inherited them: what
 * counts as true, {@code str()}, iterating {@code value or []}, {@code mapping[key]},
 * {@code key in value}, {@code value[0]}, {@code int()} and {@code float()}; a value read
 * as a mapping is {@link PythonMappings}'. Where Python fails on a value of the wrong
 * kind, these fail with Python's words, as an {@link ImportShapeException} the importer
 * refuses the text with.
 */
final class PythonValues {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private PythonValues() {
	}

	static boolean truthy(JsonNode value) {
		return PythonText.truthy(value);
	}

	static String str(JsonNode value) {
		return PythonText.str(value);
	}

	/**
	 * {@code str(value or "")}.
	 * @param value the value
	 * @return its text; empty when it counts as false
	 */
	static String strOrEmpty(JsonNode value) {
		return truthy(value) ? str(value) : "";
	}

	/**
	 * What iterating {@code value or []} gives.
	 * @param value the value
	 * @return a list's items, a mapping's keys, a text's characters; none when the value
	 * counts as false
	 * @throws ImportShapeException for a number or a boolean that counts as true
	 */
	static List<JsonNode> items(JsonNode value) {
		List<JsonNode> items = new ArrayList<>();
		if (!truthy(value)) {
			return items;
		}
		if (value.isArray()) {
			value.forEach(items::add);
		}
		else if (value.isObject()) {
			value.propertyNames().forEach((key) -> items.add(NODES.stringNode(key)));
		}
		else if (value.isString()) {
			value.stringValue()
				.codePoints()
				.forEach((character) -> items.add(NODES.stringNode(Character.toString(character))));
		}
		else {
			throw new ImportShapeException("'" + type(value) + "' object is not iterable");
		}
		return items;
	}

	/**
	 * {@code mapping[key]}.
	 * @param mapping the value indexed
	 * @param key the key
	 * @return the value at the key
	 * @throws ImportShapeException when the mapping has no such key, or is not a mapping
	 */
	static JsonNode item(JsonNode mapping, String key) {
		if (mapping.isObject()) {
			JsonNode value = mapping.get(key);
			if (value == null) {
				throw new ImportShapeException("missing " + PythonText.repr(key));
			}
			return value;
		}
		if (mapping.isArray()) {
			throw new ImportShapeException("list indices must be integers or slices, not str");
		}
		if (mapping.isString()) {
			throw new ImportShapeException("string indices must be integers, not 'str'");
		}
		throw new ImportShapeException("'" + type(mapping) + "' object is not subscriptable");
	}

	/**
	 * {@code key in value}, as Python tests it: a mapping's key, a list's item, a text's
	 * part.
	 * @param value the value, {@code value or {}}
	 * @param key the key
	 * @return whether the value holds it
	 * @throws ImportShapeException for a number or a boolean that counts as true
	 */
	static boolean contains(JsonNode value, String key) {
		if (!truthy(value)) {
			return false;
		}
		if (value.isObject()) {
			return value.has(key);
		}
		if (value.isArray()) {
			for (JsonNode item : value) {
				if (item.isString() && item.stringValue().equals(key)) {
					return true;
				}
			}
			return false;
		}
		if (value.isString()) {
			return value.stringValue().contains(key);
		}
		throw new ImportShapeException("argument of type '" + type(value) + "' is not iterable");
	}

	/**
	 * {@code key in value}, of a value as it is: unlike {@code key in (value or {})}, a
	 * value that counts as false fails too, unless it is an empty list, mapping or text.
	 * @param key the key
	 * @param value the value
	 * @return whether the value holds the key
	 * @throws ImportShapeException for {@code null}, a number or a boolean
	 */
	static boolean isIn(String key, JsonNode value) {
		if (value.isContainer() || value.isString()) {
			return contains(value, key);
		}
		throw new ImportShapeException("argument of type '" + type(value) + "' is not iterable");
	}

	/**
	 * {@code value[0]} of a value that counts as true.
	 * @param value a list, a mapping or a text
	 * @return its first item, or a text's first character
	 * @throws ImportShapeException for a mapping without the key {@code 0}
	 */
	static JsonNode first(JsonNode value) {
		if (value.isArray()) {
			return value.get(0);
		}
		if (value.isString()) {
			return NODES.stringNode(Character.toString(value.stringValue().codePointAt(0)));
		}
		if (value.isObject()) {
			throw new ImportShapeException("missing 0");
		}
		throw new ImportShapeException("'" + type(value) + "' object is not subscriptable");
	}

	/**
	 * A name matched against a pattern, which Python needs as text.
	 * @param name the name, as written
	 * @return its text
	 * @throws ImportShapeException when it is not text
	 */
	static String matchedText(JsonNode name) {
		if (name.isString()) {
			return name.stringValue();
		}
		throw new ImportShapeException("expected string or bytes-like object, got '" + type(name) + "'");
	}

	/**
	 * {@code value[:length]}.
	 * @param value the value
	 * @param length how many characters or items to keep
	 * @return a text's first characters, or a list's first items
	 * @throws ImportShapeException for a mapping, which has no such key, or a value that
	 * is neither
	 */
	static JsonNode sliced(JsonNode value, int length) {
		if (value.isString()) {
			String text = value.stringValue();
			return (text.codePointCount(0, text.length()) <= length) ? value
					: NODES.stringNode(text.substring(0, text.offsetByCodePoints(0, length)));
		}
		if (value.isArray()) {
			ArrayNode items = NODES.arrayNode();
			value.valueStream().limit(length).forEach(items::add);
			return items;
		}
		if (value.isObject()) {
			throw new ImportShapeException("missing slice(None, " + length + ", None)");
		}
		throw new ImportShapeException("'" + type(value) + "' object is not subscriptable");
	}

	/**
	 * A value used as a mapping's key, as the import keys its parts by name: its text.
	 * @param value the value
	 * @return its text, as {@code str()} writes it
	 * @throws ImportShapeException for a list or a mapping, which Python cannot hash
	 */
	static String key(JsonNode value) {
		if (value.isContainer()) {
			throw unhashable(value);
		}
		return str(value);
	}

	/**
	 * Python's failure to hash a value used as a key, or looked up in a set.
	 * @param value a list or a mapping
	 * @return the failure
	 */
	static ImportShapeException unhashable(JsonNode value) {
		return new ImportShapeException("unhashable type: '" + type(value) + "'");
	}

	/**
	 * A number divided by 100, as Python's true division rounds it.
	 * @param value a number or a boolean
	 * @return the fraction
	 */
	static double percent(JsonNode value) {
		return divided(value, HUNDRED);
	}

	/**
	 * {@code value / divisor}, as Python's true division rounds it: a whole number or a
	 * boolean divided exactly, then rounded once to a float; a float as floats divide.
	 * @param value a number or a boolean
	 * @param divisor a whole number whose quotients end, such as 100 or 1024
	 * @return the quotient
	 * @throws ImportRefusedException with Python's words for a quotient beyond a float's
	 * range
	 */
	static double divided(JsonNode value, BigDecimal divisor) {
		if (value.isBoolean() || value.isIntegralNumber()) {
			try {
				return PythonNumbers.divide(toInt(value), divisor);
			}
			catch (NumberFormatException ex) {
				throw new ImportRefusedException(String.valueOf(ex.getMessage()), ex);
			}
		}
		return value.doubleValue() / divisor.doubleValue();
	}

	/**
	 * {@code max(first, second)}.
	 * @param first a number
	 * @param second another
	 * @return the first, unless the second is greater
	 */
	static double max(double first, double second) {
		return (second > first) ? second : first;
	}

	/**
	 * {@code min(first, second)}.
	 * @param first a number
	 * @param second another
	 * @return the first, unless the second is less
	 */
	static double min(double first, double second) {
		return (second < first) ? second : first;
	}

	/**
	 * {@code isinstance(value, int)}, which a boolean is.
	 * @param value the value
	 * @return {@code true} for a whole number or a boolean
	 */
	static boolean isInt(JsonNode value) {
		return value.isIntegralNumber() || value.isBoolean();
	}

	/**
	 * {@code isinstance(value, int | float)}, which a boolean is.
	 * @param value the value
	 * @return {@code true} for a number or a boolean
	 */
	static boolean isNumber(JsonNode value) {
		return value.isNumber() || value.isBoolean();
	}

	/**
	 * {@code float(value)}.
	 * @param value the value
	 * @return its float
	 * @throws ImportRefusedException with Python's words for text that is no float, or a
	 * whole number beyond a float's range
	 * @throws ImportShapeException for a value of another kind
	 */
	static double toFloat(JsonNode value) {
		if (value.isBoolean()) {
			return value.booleanValue() ? 1 : 0;
		}
		if (value.isFloatingPointNumber()) {
			return value.doubleValue();
		}
		if (value.isIntegralNumber() || value.isString()) {
			try {
				return value.isString() ? PythonNumbers.parseFloat(value.stringValue())
						: PythonNumbers.toFloat(value.bigIntegerValue());
			}
			catch (NumberFormatException ex) {
				throw new ImportRefusedException(String.valueOf(ex.getMessage()), ex);
			}
		}
		throw new ImportShapeException("float() argument must be a string or a real number, not '" + type(value) + "'");
	}

	/**
	 * {@code int(value)}.
	 * @param value the value
	 * @return its whole number, a float truncated
	 * @throws ImportRefusedException with Python's words for text that is no whole number
	 * @throws ImportShapeException for a value of another kind
	 */
	static BigInteger toInt(JsonNode value) {
		try {
			if (value.isBoolean()) {
				return value.booleanValue() ? BigInteger.ONE : BigInteger.ZERO;
			}
			if (value.isIntegralNumber()) {
				return value.bigIntegerValue();
			}
			if (value.isNumber()) {
				return PythonNumbers.truncate(value.doubleValue());
			}
			if (value.isString()) {
				return PythonNumbers.parseInt(value.stringValue());
			}
		}
		catch (NumberFormatException ex) {
			throw new ImportRefusedException(String.valueOf(ex.getMessage()), ex);
		}
		throw new ImportShapeException(
				"int() argument must be a string, a bytes-like object or a real number, not '" + type(value) + "'");
	}

	/**
	 * A whole number as JSON.
	 * @param value the number
	 * @return its JSON
	 */
	static JsonNode number(BigInteger value) {
		return NODES.numberNode(value);
	}

	/**
	 * The name Python gives a value's type.
	 * @param value the value
	 * @return {@code NoneType}, {@code bool}, {@code int}, {@code float}, {@code str},
	 * {@code list} or {@code dict}
	 */
	static String type(JsonNode value) {
		if (value.isNull() || value.isMissingNode()) {
			return "NoneType";
		}
		if (value.isBoolean()) {
			return "bool";
		}
		if (value.isIntegralNumber()) {
			return "int";
		}
		if (value.isNumber()) {
			return "float";
		}
		if (value.isString()) {
			return "str";
		}
		return value.isArray() ? "list" : "dict";
	}

}
