package io.archsim.engine.calibration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.LinkLatencySource;
import io.archsim.engine.calibration.prometheus.PrometheusSeries;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.provenance.InputPaths;
import io.archsim.model.text.PythonFormat;

/**
 * Links' network latency, as the Python engine's {@code calibrate_prometheus} sets it:
 * half the gap between the client's and the server's mean latency of the same calls, one
 * way.
 */
final class LinkLatencies {

	private static final int LATENCY_DIGITS = 4;

	private LinkLatencies() {
	}

	/**
	 * Sets every mapped link's latency, noting links the design does not have.
	 * @param run the calibration
	 * @param mapping the mapping
	 */
	static void calibrate(PrometheusRun run, CalibrationMapping mapping) {
		mapping.edges().forEach((edgeId, sources) -> {
			ObjectNode edge = run.run().edge(edgeId);
			if (edge == null) {
				run.run().note("mapping names link '" + edgeId + "', which is not in the design: skipped");
				return;
			}
			sources.forEach((key, source) -> set(run, edge, key, source));
		});
	}

	private static void set(PrometheusRun run, ObjectNode edge, String key, LinkLatencySource source) {
		double clientMean = PrometheusSeries.histogram(run.query(source.client()), source.unit()).meanUpper();
		double serverMean = PrometheusSeries.histogram(run.query(source.server()), source.unit()).meanUpper();
		double oneWay = Math.max(0.0, clientMean - serverMean) / 2;
		ObjectNode config = CalibrationRun.config(edge);
		JsonNode before = config.has(key) ? config.get(key) : config.nullNode();
		ObjectNode value = config.objectNode()
			.put("kind", "constant")
			.put("value", PythonRound.round(oneWay, LATENCY_DIGITS));
		Change change = new Change(InputPaths.edge(edge.path("id").asString(), key),
				edge.path("source").asString() + " → " + edge.path("target").asString() + ": network latency", before,
				value, null, source.client());
		config.set(key, value.deepCopy());
		String note = "(client mean " + PythonFormat.format(clientMean, ".3f") + " ms - server mean "
				+ PythonFormat.format(serverMean, ".3f") + " ms) / 2, one way";
		run.run()
			.measured(run.run().record("client: " + source.client() + "; server: " + source.server(), null, note),
					change);
	}

}
