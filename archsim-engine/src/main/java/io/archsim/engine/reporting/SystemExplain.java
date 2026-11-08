package io.archsim.engine.reporting;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.components.client.Cohort;
import io.archsim.engine.explain.Formula;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.text.NumberText;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.text.PythonFormat;

/**
 * A report's {@code explain}: the calculations behind the run's own figures (throughput,
 * success rate, retry amplification) and where a request's time goes.
 */
final class SystemExplain {

	private static final String THOUSANDS = ",";

	private static final int BREAKDOWN_DIGITS = 2;

	private SystemExplain() {
	}

	static ObjectNode write(SimulatedRun run, ObjectNode summary, Labels labels) {
		LatencyBreakdown breakdown = new LatencyBreakdown(run.context().traces());
		ArrayNode rows = breakdown.write(labels);
		ArrayNode system = Figures.NODES.arrayNode();
		Cohort measured = run.clients().measured();
		if (measured.arrived() != 0) {
			addRunFormulas(system, run, summary);
		}
		if (!rows.isEmpty()) {
			system.add(breakdownFormula(rows, breakdown.served()));
		}
		ObjectNode explain = Figures.NODES.objectNode();
		explain.set("system", system);
		explain.set("latencyBreakdown", rows);
		return explain;
	}

	private static void addRunFormulas(ArrayNode system, SimulatedRun run, ObjectNode summary) {
		ClientMetrics clients = run.clients();
		Cohort measured = clients.measured();
		system.add(json(Formula
			.of("throughput", "Throughput", "throughput = successful requests ÷ measurement window",
					count(measured.succeeded()) + " ÷ " + NumberText.formula(run.windowSeconds(), 1) + " s")
			.withResult(value(summary.get("throughputRps")), "req/s")
			.withMetric("throughputRps")));
		system.add(json(Formula
			.of("success_rate", "Success rate", "success rate = succeeded ÷ arrived",
					count(measured.succeeded()) + " ÷ " + count(measured.arrived()))
			.withResult(value(summary.get("successRate")), "")
			.withNote("Failed " + count(measured.errors()) + ", timed out " + count(measured.timeouts()) + ".")));
		long offered = clients.offered().windowCount();
		if (offered != 0) {
			system.add(json(Formula
				.of("retry_amplification", "Retry amplification", "attempts ÷ requests",
						count(clients.attempts().windowCount()) + " ÷ " + count(offered))
				.withResult(value(summary.get("retryAmplification")), "x")
				.withNote("Above 1, retries add load exactly when the system is struggling.")
				.withMetric("attemptRps")));
		}
	}

	private static ObjectNode breakdownFormula(ArrayNode rows, int served) {
		JsonNode top = rows.get(0);
		double total = FloatSum.of(rows.valueStream().mapToDouble((row) -> row.get("meanMs").asDouble()));
		return json(Formula
			.of("latency_breakdown", "Where a request's time goes",
					"time in component = its handling time − time spent in its downstream calls",
					"mean over " + count(served) + " traced requests")
			.withResult(total, "ms")
			.withNote("Largest share: " + top.get("label").stringValue() + " with "
					+ NumberText.percent(top.get("share").asDouble()) + " ("
					+ NumberText.formula(top.get("meanMs").asDouble(), BREAKDOWN_DIGITS) + " ms).")
			.withMetric("latencyP50Ms"));
	}

	private static ObjectNode json(Formula formula) {
		return RecordCodec.write(formula);
	}

	private static @Nullable Double value(JsonNode figure) {
		return figure.isNull() ? null : figure.asDouble();
	}

	private static String count(long value) {
		return PythonFormat.format(value, THOUSANDS);
	}

}
