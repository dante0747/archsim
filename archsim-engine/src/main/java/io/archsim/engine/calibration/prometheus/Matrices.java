package io.archsim.engine.calibration.prometheus;

import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import io.archsim.model.text.PythonText;

/**
 * A Prometheus API response to a range query, checked as the Python engine's
 * {@code _matrix} checks it: a success, a matrix, and series of objects. What the server
 * said goes only into the exception's detail.
 */
public final class Matrices {

	/**
	 * Prometheus' own {@code errorType} values: the only part of an error that is
	 * repeated.
	 */
	private static final Set<String> ERROR_TYPES = Set.of("bad_data", "execution", "timeout", "canceled", "internal",
			"unavailable", "not_found");

	/** The most characters of what the server said that the detail keeps. */
	private static final int DETAIL_LENGTH = 2000;

	private Matrices() {
	}

	/**
	 * A response's series.
	 * @param body the response
	 * @param query the query it answers, for the refusals
	 * @return the series
	 * @throws PrometheusException when it is not an API response, a failure, not a
	 * matrix, or a malformed one
	 */
	public static ArrayNode of(JsonNode body, String query) {
		if (!body.isObject()) {
			throw new PrometheusException("not a Prometheus API response for: " + query);
		}
		if (!"success".equals(body.path("status").asString(null))) {
			throw failed(body, query);
		}
		JsonNode data = body.path("data");
		if (!data.isObject() || !"matrix".equals(data.path("resultType").asString(null))) {
			throw new PrometheusException("expected a range (matrix) result for: " + query);
		}
		return series(data.path("result"), query);
	}

	private static ArrayNode series(JsonNode result, String query) {
		if (!PythonText.truthy(result)) {
			return JsonNodeFactory.instance.arrayNode();
		}
		if (!result.isArray()) {
			throw malformed(query);
		}
		for (JsonNode series : result) {
			if (!series.isObject() || !objectOrFalsy(series.path("metric")) || !arrayOrFalsy(series.path("values"))) {
				throw malformed(query);
			}
		}
		return (ArrayNode) result.deepCopy();
	}

	private static PrometheusException failed(JsonNode body, String query) {
		JsonNode kind = body.path("errorType");
		String named = (kind.isString() && ERROR_TYPES.contains(kind.stringValue())) ? kind.stringValue() : "error";
		JsonNode said = body.path("error");
		String detail = (said.isMissingNode() || said.isNull()) ? null : firstCodePoints(PythonText.str(said));
		return new PrometheusException("query failed (" + named + "): " + query, detail);
	}

	private static PrometheusException malformed(String query) {
		return new PrometheusException("a malformed matrix result for: " + query);
	}

	private static boolean objectOrFalsy(JsonNode value) {
		return !PythonText.truthy(value) || value.isObject();
	}

	private static boolean arrayOrFalsy(JsonNode value) {
		return !PythonText.truthy(value) || value.isArray();
	}

	private static String firstCodePoints(String text) {
		return (text.codePointCount(0, text.length()) <= DETAIL_LENGTH) ? text
				: text.substring(0, text.offsetByCodePoints(0, DETAIL_LENGTH));
	}

}
