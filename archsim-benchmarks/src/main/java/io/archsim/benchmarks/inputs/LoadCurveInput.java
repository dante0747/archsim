package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.calibration.resources.CurveExtensions;
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
 * the service time is; flat above the top rate, unless asked for the log form
 * ({@link CurveForm}). Protocols before v5 had no staircase.
 */
final class LoadCurveInput {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MS_PER_S = 1000.0;

	private static final int RATE_DECIMALS = 1;

	private static final int FACTOR_DECIMALS = 4;

	private LoadCurveInput() {
	}

	/**
	 * The service's load curve, from the calibration's {@code cpuByLoad}: flat above the
	 * top rate ({@link CurveForm#FLAT}), so it passes neither the staircase's passes nor
	 * the service's CPUs, which only the log form uses ({@link CurveForm} says what
	 * trying it takes).
	 * @param passes the calibration's passes
	 * @return the input; empty without a staircase, or when the curve spans no more than
	 * the engine's least change
	 */
	static Optional<ObjectNode> of(JsonNode passes) {
		return curve(PythonValues.get(passes, "cpuByLoad"), List.of(), 1, CurveForm.FLAT);
	}

	/**
	 * The service's load curve in a form.
	 * @param byLoad each staircase rate's CPU per request net of idle, the median of its
	 * passes ({@code cpuByLoad})
	 * @param passes each staircase pass's rate and CPU per request
	 * ({@link #staircaseCpu}), which the log form carries the fall on from
	 * @param cores the service's CPUs, whose capacity the log form stops at
	 * @param form how the curve continues above the top rate
	 * @return the input; empty without a staircase, or when the curve spans no more than
	 * the engine's least change
	 */
	static Optional<ObjectNode> curve(JsonNode byLoad, List<RatePoint> passes, double cores, CurveForm form) {
		List<RatePoint> points = new ArrayList<>();
		for (JsonNode rate : byLoad.valueStream().toList()) {
			JsonNode perRequest = PythonValues.get(rate, "serviceCpuMsPerRequest");
			if (PythonText.truthy(perRequest)) {
				points.add(new RatePoint(rate.get("rateRps").doubleValue(),
						rate.get("rateRps").doubleValue() * perRequest.doubleValue() / MS_PER_S));
			}
		}
		if (points.size() < 2) {
			return Optional.empty();
		}
		return LoadCurves.fit(points, null, points.size()).flatMap((fit) -> input(fit, above(passes, cores, form)));
	}

	// how the curve goes on above the top rate: flat, or carried on by the extension
	private static Above above(List<RatePoint> passes, double cores, CurveForm form) {
		if (form != CurveForm.LOG || passes.isEmpty()) {
			return Above.FLAT;
		}
		return CurveExtensions.extend(passes, cores, CurveExtensions.Bounds.DEFAULT)
			.map((carried) -> new Above(carried.factors(),
					carried.points().isEmpty() ? Above.FLAT.how() : "above it " + carried.reason() + ", then flat"))
			.orElse(Above.FLAT);
	}

	/**
	 * Each staircase pass's rate and the service's CPU per request net of idle, as
	 * {@code cpuByLoad} takes their median.
	 * @param staircase the calibration's staircase passes
	 * @param idle its idle pass
	 * @return each pass's rate and milliseconds; none without the idle pass (before
	 * protocol v5)
	 */
	static List<RatePoint> staircaseCpu(JsonNode staircase, JsonNode idle) {
		if (!PythonText.truthy(idle)) {
			return List.of();
		}
		double rest = idle.get("serviceCpuMsPerSecond").doubleValue() * Protocol.CALIBRATION_S;
		return staircase.valueStream()
			.filter((pass) -> PythonText.truthy(pass.path("service").path("requests")))
			.map((pass) -> {
				JsonNode service = pass.get("service");
				return new RatePoint(pass.get("rateRps").doubleValue(),
						Math.max(0.0, service.get("cpuMs").doubleValue() - rest)
								/ Math.max(service.get("requests").doubleValue(), 1));
			})
			.toList();
	}

	// the fit's factors, then those above the top rate, and how the curve goes on there
	private static Optional<ObjectNode> input(LoadCurve fit, Above above) {
		double top = fit.points().getLast().value();
		// each rate's CPU per request relative to the top rate's
		List<RatePoint> factors = new ArrayList<>(
				fit.points().stream().map((point) -> new RatePoint(point.rate(), point.value() / top)).toList());
		factors.addAll(above.factors());
		double largest = factors.stream().mapToDouble(RatePoint::value).max().orElseThrow();
		double smallest = factors.stream().mapToDouble(RatePoint::value).min().orElseThrow();
		if (largest / smallest - 1.0 <= LoadCurves.MIN_CHANGE) {
			return Optional.empty();
		}
		List<String> measured = fit.measured()
			.stream()
			.map((point) -> PythonFormat.format(point.value(), ".4f") + " ms at "
					+ PythonFormat.format(point.rate(), "g"))
			.toList();
		ObjectNode input = NODES.objectNode().set("value", table(factors));
		input.put("how",
				"the staircase's CPU per request net of idle (" + String.join(", ", measured) + " req/s), "
						+ "fitted never rising with rate and divided by the top rate's ("
						+ PythonFormat.format(fit.measured().getLast().value(), ".4f") + " ms); " + above.how());
		return Optional.of(input);
	}

	private static ArrayNode table(List<RatePoint> factors) {
		ArrayNode table = NODES.arrayNode();
		factors.forEach((factor) -> table.addObject()
			.put("rps", PythonRound.round(factor.rate(), RATE_DECIMALS))
			.put("factor", PythonRound.round(factor.value(), FACTOR_DECIMALS)));
		return table;
	}

	/**
	 * How the curve goes on above the top rate.
	 *
	 * @param factors the rates and factors above it; none when flat
	 * @param how the curve's description from there
	 */
	private record Above(List<RatePoint> factors, String how) {

		/**
		 * Flat, worded as engine 0.15.0 wrote it: every kept run's inputs stay the same.
		 */
		static final Above FLAT = new Above(List.of(), "flat outside the staircase");

	}

}
