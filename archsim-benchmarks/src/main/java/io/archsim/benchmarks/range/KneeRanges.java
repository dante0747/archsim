package io.archsim.benchmarks.range;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonStatistics;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.text.PythonText;

/**
 * The predicted range of the knee: a lognormal around the predicted knee whose spread
 * adds the calibration's run-to-run variation for the design and the model's own error,
 * cut at a central share. It holds the measured knee that share of the time if the
 * in-sample runs it was fitted on are like the runs it is used on (the held-out campaign
 * tests that).
 */
public final class KneeRanges {

	/**
	 * The range's nominal coverage: the share of runs whose measured knee it should hold.
	 * 80%: its low end is a load to plan on that about one run in ten falls below. A 50%
	 * range is missed every other run, too often to plan on; a 90% range's ends would
	 * rest on the one or two largest errors of a dozen runs.
	 */
	public static final double COVERAGE = 0.8;

	/** How a range is built, in its own words. */
	static final String METHOD = "the knee's run-to-run variation from a fresh calibration (each input's variation "
			+ "between calibrations times how much the knee moves with it) and the model's own error (the spread of "
			+ "measured over predicted knees in the in-sample runs), as a lognormal around the predicted knee";

	private static final List<String> FIT_KEYS = List.of("at", "engineVersion", "commit", "runs", "experiments");

	private static final int DECIMALS = 4;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private KneeRanges() {
	}

	/**
	 * The range at a coverage.
	 * @param nominal the predicted knee
	 * @param sensitivity the knee's sensitivity to each varied input
	 * @param fit the fit, or None
	 * @param coverage the share of the lognormal the range holds, its centre
	 * @return the range: its ends, spread and parts, and the fit it came from; empty
	 * without a fit or a knee
	 */
	static Optional<ObjectNode> of(JsonNode nominal, JsonNode sensitivity, JsonNode fit, double coverage) {
		if (PythonValues.isNone(fit) || !PythonText.truthy(nominal)) {
			return Optional.empty();
		}
		double calibration = calibrationSigma(sensitivity, fit);
		double model = fit.get("model").get("sigma").doubleValue();
		double sigma = Math.sqrt(calibration * calibration + model * model);
		double quantile = PythonStatistics.normalInverse((1 + coverage) / 2);
		ObjectNode range = NODES.objectNode().put("method", METHOD);
		range.set("lowRps", roundToInteger(nominal.doubleValue() * StrictMath.exp(-quantile * sigma)));
		range.set("highRps", roundToInteger(nominal.doubleValue() * StrictMath.exp(quantile * sigma)));
		range.set("nominalRps", nominal);
		range.put("nominalCoverage", coverage);
		range.put("sigma", PythonRound.round(sigma, DECIMALS));
		range.put("calibrationSigma", PythonRound.round(calibration, DECIMALS));
		range.put("modelSigma", PythonRound.round(model, DECIMALS));
		range.set("sensitivity", sensitivity.deepCopy());
		ObjectNode from = range.putObject("fit");
		FIT_KEYS.forEach((key) -> from.set(key, PythonValues.get(fit, key)));
		return Optional.of(range);
	}

	/**
	 * The spread (log standard deviation) of the knee a fresh calibration would give:
	 * each input's variation times the knee's sensitivity to it, with one common
	 * correlation between any two inputs (a faster or slower day moves several at once):
	 * {@code (1 - rho) * sum(a^2) + rho * (sum a)^2}, {@code a} the sensitivity times the
	 * variation.
	 * @param sensitivity the knee's sensitivity to each varied input
	 * @param fit the fit
	 * @return the spread
	 */
	static double calibrationSigma(JsonNode sensitivity, JsonNode fit) {
		List<Double> parts = new ArrayList<>();
		List<Double> squares = new ArrayList<>();
		sensitivity.properties().forEach((input) -> {
			double part = input.getValue().doubleValue() * inputSigma(fit, input.getKey());
			parts.add(part);
			squares.add(part * part);
		});
		double rho = PythonValues.numberOrZero(fit, "correlation");
		double sum = PythonValues.sumOfFloats(parts);
		double variance = (1 - rho) * PythonValues.sumOfFloats(squares) + rho * (sum * sum);
		return Math.sqrt(Math.max(variance, 0.0));
	}

	/**
	 * An input's run-to-run variation (log standard deviation) in the fit, or the fit's
	 * fallback (the median over the inputs it measured) for one it never saw vary.
	 * @param fit the fit
	 * @param key the input
	 * @return the variation
	 */
	static double inputSigma(JsonNode fit, String key) {
		JsonNode sigma = fit.path("inputs").path(key).path("sigma");
		return (sigma.isNumber() ? sigma : fit.get("fallbackSigma")).doubleValue();
	}

	// Python's round(x): half to even, an int
	private static JsonNode roundToInteger(double value) {
		return PythonValues.integer((long) Math.rint(value));
	}

}
