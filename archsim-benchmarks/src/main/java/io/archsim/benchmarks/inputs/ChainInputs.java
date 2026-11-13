package io.archsim.benchmarks.inputs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * Chain's API behind the gateway, whose {@code /stats} are in the gateway's as
 * {@code upstream}: its CPU per request, found as the gateway's is, and the
 * gateway-to-API latency.
 */
final class ChainInputs {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final int DECIMALS = 4;

	private ChainInputs() {
	}

	/**
	 * The API's inputs.
	 * @param passes the calibration's passes
	 * @return its service time and the gateway-to-API latency
	 */
	static ObjectNode of(JsonNode passes) {
		JsonNode high = passes.get("high");
		JsonNode api = high.get("service").get("upstream");
		JsonNode apiLow = passes.get("low").get("service").get("upstream");
		JsonNode own = PassReadings.timing(api, "own");
		JsonNode handler = PassReadings.timing(api, "handler");
		JsonNode call = PassReadings.timing(high.get("service"), "upstream");
		Double slope = PassReadings.slope(apiLow.get("cpuMs"), api.get("cpuMs"), apiLow.get("requests").doubleValue(),
				api.get("requests").doubleValue());
		double demand = (slope != null && slope > 0) ? slope : api.get("cpuMsPerRequest").doubleValue();
		ObjectNode inputs = NODES.objectNode();
		inputs.putObject("apiServiceTimeMs")
			.set("value", ModelDistributions.lognormalWithMean(demand, own))
			.put("how", "mean = the API's CPU per extra request between the passes: ("
					+ PythonFormat.format(api.get("cpuMs").doubleValue(), ".0f") + " - "
					+ PythonFormat.format(apiLow.get("cpuMs").doubleValue(), ".0f") + " ms) / ("
					+ PythonText.str(api.get("requests")) + " - " + PythonText.str(apiLow.get("requests"))
					+ " requests) = " + PythonFormat.format(demand, ".4f") + " ms; shape (p95/median "
					+ PythonFormat.format(own.get("p95").doubleValue(), ".4f") + "/"
					+ PythonFormat.format(own.get("median").doubleValue(), ".4f") + " ms) from its handler's own time");
		double callMedian = call.get("median").doubleValue();
		double handlerMedian = handler.get("median").doubleValue();
		inputs.putObject("apiLinkLatencyMs")
			.put("value", PythonRound.round(Math.max(0.0, (callMedian - handlerMedian) / 2), DECIMALS))
			.put("how",
					"(the gateway's call to the API, median " + PythonFormat.format(callMedian, ".3f")
							+ " ms - the API's handler median " + PythonFormat.format(handlerMedian, ".3f")
							+ " ms) / 2, one way");
		return inputs;
	}

}
