package io.archsim.benchmarks.range;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.values.PythonStatistics;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.numeric.PythonRound;

/**
 * Each input's run-to-run variation over calibrations: the log standard deviation of its
 * scale around its experiment's mean, pooled over the experiments (an experiment's n
 * calibrations count n - 1 times); the median of those for inputs never measured or never
 * seen moving; and one common correlation between inputs, the mean product of two inputs'
 * standardised deviations in the same calibration, at least 0.
 */
final class VariationFit {

	/**
	 * The least variation an input must show to count as varying: one whose value never
	 * moved (a fixed default such as Kafka's fetch overhead in the kept runs, or a share
	 * at its cap) takes the fallback, not none.
	 */
	static final double MIN_SIGMA = 0.001;

	private static final int DECIMALS = 4;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private VariationFit() {
	}

	/**
	 * The variation fit.
	 * @param rows one per calibration: its {@code experiment} and each varied input's
	 * {@code scales}
	 * @return {@code inputs} (each one's sigma, calibrations and experiments),
	 * {@code fallbackSigma} and {@code correlation}
	 */
	static ObjectNode of(List<JsonNode> rows) {
		Map<String, List<JsonNode>> byExperiment = new LinkedHashMap<>();
		rows.forEach(
				(row) -> byExperiment.computeIfAbsent(row.get("experiment").stringValue(), (name) -> new ArrayList<>())
					.add(row.get("scales")));
		ObjectNode inputs = NODES.objectNode();
		Map<String, List<Deviation>> deviations = new LinkedHashMap<>();
		for (String key : RangeInputs.VARIED) {
			Pooled pooled = pooled(byExperiment, key, deviations);
			if (pooled.dof() > 0 && Math.sqrt(pooled.squares() / pooled.dof()) >= MIN_SIGMA) {
				inputs.putObject(key)
					.put("sigma", PythonRound.round(Math.sqrt(pooled.squares() / pooled.dof()), DECIMALS))
					.put("calibrations", pooled.calibrations())
					.put("experiments", pooled.experiments());
			}
		}
		List<JsonNode> sigmas = inputs.valueStream().map((input) -> input.get("sigma")).toList();
		ObjectNode fit = NODES.objectNode().set("inputs", inputs);
		fit.put("fallbackSigma",
				sigmas.isEmpty() ? 0.0 : PythonRound.round(PythonValues.median(sigmas).doubleValue(), DECIMALS));
		fit.put("correlation", PythonRound.round(Math.min(correlation(deviations, inputs), 1.0), DECIMALS));
		return fit;
	}

	// an input's squared log deviations from each experiment's mean, pooled, and each
	// deviation kept for the correlation
	private static Pooled pooled(Map<String, List<JsonNode>> byExperiment, String key,
			Map<String, List<Deviation>> deviations) {
		double squares = 0.0;
		int dof = 0;
		int experiments = 0;
		int calibrations = 0;
		for (Map.Entry<String, List<JsonNode>> experiment : byExperiment.entrySet()) {
			Logs found = logs(experiment.getValue(), key);
			List<Integer> rows = found.rows();
			List<Double> logs = found.values();
			if (logs.size() < 2) {
				continue;
			}
			double mean = PythonStatistics.fmean(logs);
			List<Double> squared = new ArrayList<>();
			for (int index = 0; index < logs.size(); index++) {
				double deviation = logs.get(index) - mean;
				deviations.computeIfAbsent(key, (name) -> new ArrayList<>())
					.add(new Deviation(experiment.getKey(), rows.get(index), deviation));
				squared.add(deviation * deviation);
			}
			squares += PythonValues.sumOfFloats(squared);
			dof += logs.size() - 1;
			experiments++;
			calibrations += logs.size();
		}
		return new Pooled(squares, dof, experiments, calibrations);
	}

	// the log of an input's scale in each of an experiment's calibrations that has one
	private static Logs logs(List<JsonNode> scales, String key) {
		List<Integer> rows = new ArrayList<>();
		List<Double> logs = new ArrayList<>();
		for (int row = 0; row < scales.size(); row++) {
			double scale = scales.get(row).path(key).asDouble(0);
			if (scale > 0) {
				rows.add(row);
				logs.add(StrictMath.log(scale));
			}
		}
		return new Logs(rows, logs);
	}

	// the mean product of two inputs' standardised deviations in one calibration, each
	// experiment's scaled up by n / (n - 1) for its own mean; at least 0
	private static double correlation(Map<String, List<Deviation>> deviations, ObjectNode inputs) {
		Map<String, List<Double>> byCalibration = new LinkedHashMap<>();
		for (Map.Entry<String, List<Deviation>> input : deviations.entrySet()) {
			double sigma = inputs.path(input.getKey()).path("sigma").asDouble(0);
			if (sigma <= 0) {
				continue;
			}
			for (Deviation deviation : input.getValue()) {
				long count = input.getValue()
					.stream()
					.filter((other) -> other.experiment().equals(deviation.experiment()))
					.count();
				byCalibration
					.computeIfAbsent(deviation.experiment() + "|" + deviation.row(), (name) -> new ArrayList<>())
					.add(deviation.value() / sigma * Math.sqrt((double) count / (count - 1)));
			}
		}
		List<Double> products = new ArrayList<>();
		byCalibration.values().forEach((standardised) -> products.addAll(pairProducts(standardised)));
		return products.isEmpty() ? 0.0 : Math.max(0.0, PythonStatistics.fmean(products));
	}

	// the product of each two values, in order
	private static List<Double> pairProducts(List<Double> values) {
		List<Double> products = new ArrayList<>();
		for (int first = 0; first < values.size(); first++) {
			for (int second = first + 1; second < values.size(); second++) {
				products.add(values.get(first) * values.get(second));
			}
		}
		return products;
	}

	/**
	 * An input's squared deviations pooled over the experiments.
	 *
	 * @param squares the sum of squared log deviations from each experiment's mean
	 * @param dof the degrees of freedom: each experiment's calibrations less one
	 * @param experiments the experiments with two calibrations or more
	 * @param calibrations their calibrations
	 */
	private record Pooled(double squares, int dof, int experiments, int calibrations) {

	}

	/**
	 * The logs of an input's scale in an experiment's calibrations.
	 *
	 * @param rows each one's calibration, its place among the experiment's
	 * @param values the logs
	 */
	private record Logs(List<Integer> rows, List<Double> values) {

	}

	/**
	 * One calibration's log deviation of an input from its experiment's mean.
	 *
	 * @param experiment the experiment
	 * @param row the calibration's place among the experiment's
	 * @param value the deviation
	 */
	private record Deviation(String experiment, int row, double value) {

	}

}
