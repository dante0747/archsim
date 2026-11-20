package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.machine.MachineFit;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.calibration.resources.CurveExtensions;
import io.archsim.engine.calibration.resources.LoadCurve;
import io.archsim.engine.calibration.resources.LoadCurves;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * How the service's CPU per request changes with load ({@code serviceTimeByLoad}),
 * relative to the calibration staircase's top rate, whose CPU per request the service
 * time is, in one of three forms ({@link CurveForm}): a law of the rate carried above the
 * top rate ({@link LawCurveInput}, the derivation's); the engine's never-rising fit of
 * each rate, one bin per rate, flat above the top rate; or that fit with W26's log
 * extension. Protocols before v5 had no staircase.
 */
final class LoadCurveInput {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final double MS_PER_S = 1000.0;

	private static final int RATE_DECIMALS = 1;

	private static final int FACTOR_DECIMALS = 4;

	private LoadCurveInput() {
	}

	/**
	 * The service's load curve, from the calibration's {@code cpuByLoad}, flat above the
	 * top rate ({@link CurveForm#FLAT}): every derivation's before ADR-0093, which the
	 * kept runs' recorded inputs and checks were made with.
	 * @param passes the calibration's passes
	 * @return the input; empty without a staircase, or when the curve spans no more than
	 * the engine's least change
	 */
	static Optional<ObjectNode> of(JsonNode passes) {
		return flat(PythonValues.get(passes, "cpuByLoad"));
	}

	/**
	 * The service's load curve in a form, for a design on a host machine or on none: only
	 * the law takes the host machine into account ({@link LawCurveInput#of}).
	 * @param passes the calibration's passes: {@code cpuByLoad}, and for the log form and
	 * the law on a host machine the staircase and the idle pass
	 * @param cores the service's CPUs, where the law and the log form stop
	 * @param form the curve's form
	 * @param machine the host machine's fit; {@code null} for none
	 * @return the input; empty without a staircase, or when the curve spans no more than
	 * the engine's least change
	 */
	static Optional<ObjectNode> of(JsonNode passes, int cores, CurveForm form, @Nullable MachineFit machine) {
		return switch (form) {
			case LAW -> LawCurveInput.of(passes, cores, machine);
			case FLAT -> of(passes);
			case LOG -> curve(PythonValues.get(passes, "cpuByLoad"),
					staircaseCpu(PythonValues.get(passes, "staircase"), PythonValues.get(passes, "idle")), cores,
					CurveForm.LOG);
		};
	}

	/**
	 * The service's load curve, fitted never rising and flat above the top rate.
	 * @param byLoad each staircase rate's CPU per request net of idle ({@code cpuByLoad})
	 * @return the input; empty without a staircase, or when the curve spans no more than
	 * the engine's least change
	 */
	static Optional<ObjectNode> flat(JsonNode byLoad) {
		return curve(byLoad, List.of(), 1, CurveForm.FLAT);
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
		// each rate's busy cores, which the never-rising fit weighs
		List<RatePoint> points = perRequest(byLoad).stream()
			.map((point) -> new RatePoint(point.rate(), point.rate() * point.value() / MS_PER_S))
			.toList();
		if (points.size() < 2) {
			return Optional.empty();
		}
		return LoadCurves.fit(points, null, points.size()).flatMap((fit) -> input(fit, above(passes, cores, form)));
	}

	/**
	 * Each staircase rate's CPU per request, where the staircase measured one.
	 * @param byLoad the calibration's {@code cpuByLoad}
	 * @return each rate and its milliseconds, in the staircase's order
	 */
	static List<RatePoint> perRequest(JsonNode byLoad) {
		List<RatePoint> points = new ArrayList<>();
		for (JsonNode rate : byLoad.valueStream().toList()) {
			JsonNode perRequest = PythonValues.get(rate, "serviceCpuMsPerRequest");
			if (PythonText.truthy(perRequest)) {
				points.add(new RatePoint(rate.get("rateRps").doubleValue(), perRequest.doubleValue()));
			}
		}
		return points;
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
		return staircaseCpu(staircase, idle, (pass) -> OptionalDouble.of(1.0));
	}

	/**
	 * Each staircase pass's rate and the service's CPU per request net of idle times a
	 * factor of the pass.
	 * @param staircase the calibration's staircase passes
	 * @param idle its idle pass
	 * @param factor each pass's factor; a pass without one is left out
	 * @return each pass's rate and milliseconds; none without the idle pass (before
	 * protocol v5)
	 */
	static List<RatePoint> staircaseCpu(JsonNode staircase, JsonNode idle, Function<JsonNode, OptionalDouble> factor) {
		if (!PythonText.truthy(idle)) {
			return List.of();
		}
		double rest = idle.get("serviceCpuMsPerSecond").doubleValue() * Protocol.CALIBRATION_S;
		return staircase.valueStream()
			.filter((pass) -> PythonText.truthy(pass.path("service").path("requests")))
			.flatMap((pass) -> factor.apply(pass)
				.stream()
				.mapToObj((times) -> new RatePoint(pass.get("rateRps").doubleValue(),
						netCpuPerRequest(pass, rest) * times)))
			.toList();
	}

	/**
	 * Whether factors span more than the engine's least change.
	 * @param factors the factors
	 * @return whether the largest is more than {@link LoadCurves#MIN_CHANGE} above the
	 * smallest
	 */
	static boolean spansMoreThanNoise(List<RatePoint> factors) {
		double largest = factors.stream().mapToDouble(RatePoint::value).max().orElseThrow();
		double smallest = factors.stream().mapToDouble(RatePoint::value).min().orElseThrow();
		return largest / smallest - 1.0 > LoadCurves.MIN_CHANGE;
	}

	/**
	 * Each rate's CPU per request in words.
	 * @param measured each rate and its milliseconds
	 * @return the words: "0.9139 ms at 50, 0.3047 ms at 651"
	 */
	static String words(List<RatePoint> measured) {
		return String.join(", ",
				measured.stream()
					.map((point) -> PythonFormat.format(point.value(), ".4f") + " ms at "
							+ PythonFormat.format(point.rate(), "g"))
					.toList());
	}

	/**
	 * Factors as {@code serviceTimeByLoad}'s points: each rate to a tenth, each factor to
	 * four places.
	 * @param factors the rates and factors
	 * @return the {@code {rps, factor}} points
	 */
	static ArrayNode table(List<RatePoint> factors) {
		ArrayNode table = NODES.arrayNode();
		factors.forEach((factor) -> table.addObject()
			.put("rps", PythonRound.round(factor.rate(), RATE_DECIMALS))
			.put("factor", PythonRound.round(factor.value(), FACTOR_DECIMALS)));
		return table;
	}

	// a staircase pass's CPU per request net of idle
	private static double netCpuPerRequest(JsonNode pass, double restMs) {
		JsonNode service = pass.get("service");
		return Math.max(0.0, service.get("cpuMs").doubleValue() - restMs)
				/ Math.max(service.get("requests").doubleValue(), 1);
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

	// the fit's factors, then those above the top rate, and how the curve goes on there
	private static Optional<ObjectNode> input(LoadCurve fit, Above above) {
		double top = fit.points().getLast().value();
		// each rate's CPU per request relative to the top rate's
		List<RatePoint> factors = new ArrayList<>(
				fit.points().stream().map((point) -> new RatePoint(point.rate(), point.value() / top)).toList());
		factors.addAll(above.factors());
		if (!spansMoreThanNoise(factors)) {
			return Optional.empty();
		}
		ObjectNode input = NODES.objectNode().set("value", table(factors));
		input.put("how",
				"the staircase's CPU per request net of idle (" + words(fit.measured()) + " req/s), "
						+ "fitted never rising with rate and divided by the top rate's ("
						+ PythonFormat.format(fit.measured().getLast().value(), ".4f") + " ms); " + above.how());
		return Optional.of(input);
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

		// the factors copied
		Above {
			factors = List.copyOf(factors);
		}

	}

}
