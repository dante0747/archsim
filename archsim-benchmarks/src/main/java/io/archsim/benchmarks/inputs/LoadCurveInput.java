package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.calibration.resources.LoadCurve;
import io.archsim.engine.calibration.resources.LoadCurves;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * How the service's CPU per request changed with load over the calibration staircase
 * ({@code serviceTimeByLoad}): the engine's never-rising fit of each rate's CPU per
 * request net of idle, one bin per rate, relative to the top rate, whose CPU per request
 * the service time is; flat above the top rate. Protocols before v5 had no staircase.
 */
final class LoadCurveInput {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MS_PER_S = 1000.0;

	private static final int RATE_DECIMALS = 1;

	private static final int FACTOR_DECIMALS = 4;

	private LoadCurveInput() {
	}

	/**
	 * The service's load curve, from the calibration's {@code cpuByLoad}.
	 * @param passes the calibration's passes
	 * @return the input; empty without a staircase, or when the curve spans no more than
	 * the engine's least change
	 */
	static Optional<ObjectNode> of(JsonNode passes) {
		List<RatePoint> points = new ArrayList<>();
		for (JsonNode rate : PythonValues.get(passes, "cpuByLoad").valueStream().toList()) {
			JsonNode perRequest = PythonValues.get(rate, "serviceCpuMsPerRequest");
			if (PythonText.truthy(perRequest)) {
				points.add(new RatePoint(rate.get("rateRps").doubleValue(),
						rate.get("rateRps").doubleValue() * perRequest.doubleValue() / MS_PER_S));
			}
		}
		if (points.size() < 2) {
			return Optional.empty();
		}
		return LoadCurves.fit(points, null, points.size()).flatMap(LoadCurveInput::input);
	}

	private static Optional<ObjectNode> input(LoadCurve fit) {
		double top = fit.points().getLast().value();
		// each rate's CPU per request relative to the top rate's
		List<RatePoint> factors = fit.points()
			.stream()
			.map((point) -> new RatePoint(point.rate(), point.value() / top))
			.toList();
		double largest = factors.stream().mapToDouble(RatePoint::value).max().orElseThrow();
		double smallest = factors.stream().mapToDouble(RatePoint::value).min().orElseThrow();
		if (largest / smallest - 1.0 <= LoadCurves.MIN_CHANGE) {
			return Optional.empty();
		}
		ArrayNode table = NODES.arrayNode();
		factors.forEach((factor) -> table.addObject()
			.put("rps", PythonRound.round(factor.rate(), RATE_DECIMALS))
			.put("factor", PythonRound.round(factor.value(), FACTOR_DECIMALS)));
		List<String> measured = fit.measured()
			.stream()
			.map((point) -> PythonFormat.format(point.value(), ".4f") + " ms at "
					+ PythonFormat.format(point.rate(), "g"))
			.toList();
		ObjectNode input = NODES.objectNode().set("value", table);
		input.put("how", "the staircase's CPU per request net of idle (" + String.join(", ", measured) + " req/s), "
				+ "fitted never rising with rate and divided by the top rate's ("
				+ PythonFormat.format(fit.measured().getLast().value(), ".4f") + " ms); flat outside the staircase");
		return Optional.of(input);
	}

}
