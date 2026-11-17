package io.archsim.parity;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Where the Java's output departs from the frozen engine's because schema 1.9 lets a
 * third-party API's price per million calls be left unset (ADR-0081), where the frozen
 * engine's defaulted to 0, and how it is read as the frozen engine's all the same. The
 * frozen engine's output is the guide: only where it records the price as 0 does the
 * Java's read as it.
 * <ul>
 * <li>A setting written 0 (in a design, a report's design or parts, the catalog's default
 * or its payments preset) where the Java leaves it out or writes it empty.</li>
 * <li>An input naming the setting (an analysis's inputs, a report's assumptions, an
 * input's provenance) whose value is 0, where the Java's is unset and illustrative, or 0
 * and typed or a preset's: its value and provenance, for the Java says where an unset
 * price comes from, or that a price of 0 is no longer the default.</li>
 * <li>The catalog's field for the setting, where the Java's is empty by default and may
 * be left so: its default, its description and whether it may be empty.</li>
 * </ul>
 * A run of a design that prices an API at 0 the frozen engine's way (unset, or 0 in a
 * design older than 1.9, which the Java reads as unset) records the price as unset among
 * its parts' settings, so its result digest departs, and with it the run's id that the
 * MCP server takes from it; and an API the Java does not price no longer makes the cost
 * one with a price ({@link #pricesAnApiAtZero}).
 */
public final class ApiPriceDepartures {

	/** The setting, wherever a design, a report or the catalog names it. */
	private static final String PRICE = "costPerMillionRequests";

	private static final String ILLUSTRATIVE = "illustrative";

	/** How the Java writes an input's value that is unset. */
	private static final String UNSET = "None";

	/** The start of the catalog field's description at schema 1.9. */
	private static final String DESCRIBED = "Price per million calls, used for cost estimates only. Empty: ";

	/** How the frozen engine writes an input's value of 0 dollars. */
	private static final String ZERO_DOLLARS = "0 $";

	private ApiPriceDepartures() {
	}

	/**
	 * The Java's output with the API prices the frozen engine recorded as 0 read as it.
	 * @param java the Java's output, which is not changed
	 * @param python the frozen engine's output of the same call
	 * @return a copy of the Java's output
	 */
	static JsonNode asRecorded(JsonNode java, JsonNode python) {
		JsonNode copy = java.deepCopy();
		put(copy, python);
		return copy;
	}

	/**
	 * Whether the frozen engine priced a third-party API of a design at 0: the design
	 * leaves its price unset, or sets it to 0, which the upgrade to 1.9 reads as unset in
	 * the recorded designs, all older than 1.9 and without provenance for it.
	 * @param design the design as recorded
	 * @return whether a run of it departs so
	 */
	public static boolean pricesAnApiAtZero(JsonNode design) {
		for (JsonNode node : design.path("architecture").path("nodes")) {
			JsonNode price = node.path("config").path(PRICE);
			if ("external_api".equals(node.path("type").stringValue("")) && (isUnset(price) || isZero(price))) {
				return true;
			}
		}
		return false;
	}

	private static void put(JsonNode java, JsonNode python) {
		if (java instanceof ObjectNode object && python.isObject()) {
			putObject(object, python);
			python.propertyNames().forEach((key) -> {
				if (object.has(key)) {
					put(object.get(key), python.get(key));
				}
			});
		}
		else if (java.isArray() && python.isArray()) {
			for (int index = 0; index < Math.min(java.size(), python.size()); index++) {
				put(java.get(index), python.get(index));
			}
		}
	}

	private static void putObject(ObjectNode java, JsonNode python) {
		JsonNode price = python.path(PRICE);
		if (isZero(price) && isUnset(java.path(PRICE))) {
			java.set(PRICE, price);
		}
		if (names(java, python, "field") && isZero(python.path("value")) && isUnstatedInput(java)) {
			java.set("value", python.get("value"));
			java.set("provenance", python.get("provenance"));
		}
		if (names(java, python, "key") && python.has("nullable") && isEmptiableField(java)) {
			java.set("default", python.get("default"));
			java.set("description", python.get("description"));
			java.set("nullable", python.get("nullable"));
		}
	}

	// an input of the price no person stated, as the Java reads it: unset, the default
	// (illustrative); or 0, no longer the default, so typed (assumed) or a preset's
	// (illustrative)
	private static boolean isUnstatedInput(JsonNode input) {
		JsonNode value = input.path("value");
		String kind = input.path("provenance").path("kind").stringValue("");
		if (isUnset(value) || UNSET.equals(value.stringValue(""))) {
			return ILLUSTRATIVE.equals(kind);
		}
		return isZero(value) && (ILLUSTRATIVE.equals(kind) || "assumed".equals(kind));
	}

	// the catalog's field as schema 1.9 describes it: empty by default, and may be left
	// so
	private static boolean isEmptiableField(JsonNode field) {
		boolean emptiable = field.path("default").isNull() && field.path("nullable").booleanValue();
		return emptiable && field.path("description").stringValue("").startsWith(DESCRIBED);
	}

	// whether both objects name the setting under this key
	private static boolean names(JsonNode java, JsonNode python, String key) {
		return PRICE.equals(python.path(key).stringValue("")) && PRICE.equals(java.path(key).stringValue(""));
	}

	private static boolean isUnset(JsonNode value) {
		return value.isNull() || value.isMissingNode();
	}

	private static boolean isZero(JsonNode value) {
		boolean zero = value.isNumber() && value.doubleValue() == 0;
		return zero || ZERO_DOLLARS.equals(value.stringValue(""));
	}

}
