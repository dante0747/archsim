package io.archsim.model.upgrade;

import java.util.function.UnaryOperator;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The step to schema 1.9, where a third-party API's price per million calls may be left
 * unset (ADR-0081). Before it the price defaulted to 0, and a saved design wrote the
 * default out, so a price of 0 cannot be told from one nobody set: the step leaves it
 * unset, unless the design's provenance says a person stated it (measured or assumed).
 * Any other price, and any other part's setting, is kept.
 */
final class UnsetApiPrices implements UnaryOperator<ObjectNode> {

	/**
	 * A third-party API's type, as schema 1.8 wrote it: a step reads the JSON of its
	 * schema, below the components that read the current one.
	 */
	private static final String API = "external_api";

	/** Its price per million calls, as schema 1.8 wrote it. */
	private static final String PRICE = "costPerMillionRequests";

	/** The provenance of a value no person stated. */
	private static final String ILLUSTRATIVE = "illustrative";

	@Override
	public ObjectNode apply(ObjectNode document) {
		JsonNode provenance = document.path("provenance");
		for (JsonNode node : document.path("architecture").path("nodes")) {
			JsonNode config = node.path("config");
			if (API.equals(node.path("type").stringValue("")) && config instanceof ObjectNode settings
					&& isZero(settings.get(PRICE)) && !stated(provenance, node)) {
				settings.remove(PRICE);
			}
		}
		return document;
	}

	private static boolean isZero(JsonNode price) {
		return price != null && price.isNumber() && price.doubleValue() == 0;
	}

	// whether the design's provenance records the API's price as a person's
	private static boolean stated(JsonNode provenance, JsonNode node) {
		String kind = provenance.path("nodes/" + node.path("id").stringValue("") + "/" + PRICE)
			.path("kind")
			.stringValue("");
		return !kind.isEmpty() && !ILLUSTRATIVE.equals(kind);
	}

}
