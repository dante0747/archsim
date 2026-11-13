package io.archsim.benchmarks.inputs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.docker.Stacks;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.engine.calibration.resources.MemoryFit;
import io.archsim.engine.calibration.resources.MemoryFits;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * Each service's memory: at idle, per req/s and per request held, fitted by the engine's
 * non-negative least squares to its container's working set over the staircase and at
 * rest. A garbage-collected heap grows with the rate it allocates at, not with requests
 * held, so the fit is against the rate, and memory per request held is its floor. Nothing
 * without passes at two rates or more: the model keeps its defaults.
 */
final class MemoryInputs {

	/** The staircase rates, besides rest, the fit needs. */
	private static final int MIN_RATES = 2;

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private MemoryInputs() {
	}

	/**
	 * The memory inputs of the service k6 calls, and of chain's API.
	 * @param experiment the experiment
	 * @param passes the calibration's passes, with its {@code idle} pass and
	 * {@code staircase}
	 * @return the inputs, none for a service whose memory could not be fitted
	 */
	static ObjectNode of(Experiment experiment, JsonNode passes) {
		ObjectNode inputs = NODES.objectNode();
		containers(experiment).forEach((prefix, container) -> fit(passes, container)
			.ifPresent((fit) -> add(inputs, prefix, fit.fit(), what(container, fit))));
		return inputs;
	}

	// a container's fit, over passes at two rates or more
	private static Optional<Fitted> fit(JsonNode passes, String container) {
		List<RatePoint> points = points(passes, container);
		List<Double> rates = points.stream().map(RatePoint::rate).filter((rate) -> rate > 0).toList();
		if (rates.stream().distinct().count() < MIN_RATES) {
			return Optional.empty();
		}
		return MemoryFits
			.fit(points.stream().map(RatePoint::value).toList(), points.stream().map(RatePoint::rate).toList(), null)
			.map((fit) -> new Fitted(fit, points, rates));
	}

	private static void add(ObjectNode inputs, String prefix, MemoryFit fit, String what) {
		inputs.putObject(prefix + "MemoryBaselineMb").put("value", fit.baselineMb()).put("how", "at idle: " + what);
		inputs.putObject(prefix + "MemoryPerRpsMb").put("value", fit.perRpsMb()).put("how", "per req/s: " + what);
		inputs.putObject(prefix + "MemoryPerRequestMb")
			.put("value", fit.perRequestMb())
			.put("how", "per request held: the floor, since the fit against req/s explains the whole working set "
					+ "(held requests' memory would count twice otherwise)");
	}

	// the fit in words: the container, the points, the formula and its largest error
	private static String what(String container, Fitted fitted) {
		MemoryFit fit = fitted.fit();
		List<RatePoint> points = fitted.points();
		List<Double> rates = fitted.rates();
		String error = (fit.error() != null) ? PythonFormat.format(fit.error(), ".0%") : "-";
		StringBuilder what = new StringBuilder("the " + container + " container's working set (the highest reading "
				+ "in each pass) against req/s over " + points.size() + " points ("
				+ ((rates.size() < points.size()) ? "at rest and " : "") + rates.size() + " passes, "
				+ PythonFormat.format(rates.stream().mapToDouble(Double::doubleValue).min().orElseThrow(), ",.0f")
				+ " to "
				+ PythonFormat.format(rates.stream().mapToDouble(Double::doubleValue).max().orElseThrow(), ",.0f")
				+ " req/s), by non-negative least squares: " + PythonFormat.format(fit.baselineMb(), ".1f") + " MiB + "
				+ PythonFormat.format(fit.perRpsMb(), ".4f")
				+ " MiB per req/s, largest error at and above the median rate " + error);
		fit.notes().forEach((note) -> what.append("; ").append(note));
		return what.toString();
	}

	// the services whose memory is calibrated: their inputs' prefix and their container
	private static Map<String, String> containers(Experiment experiment) {
		Map<String, String> containers = new LinkedHashMap<>();
		containers.put("service", Stacks.standard().containers(experiment.stack()).getFirst());
		if (experiment.stack().equals("chain")) {
			containers.put("api", "chain-api");
		}
		return containers;
	}

	// (req/s, MiB): the working set at rest, and the highest reading of
	// each staircase pass against the rate k6 achieved; calibrations
	// before W24 recorded neither
	private static List<RatePoint> points(JsonNode passes, String container) {
		List<RatePoint> points = new ArrayList<>();
		JsonNode rest = passes.path("idle").path("workingSetMiB").path(container);
		if (!PythonValues.isNone(rest)) {
			points.add(new RatePoint(0.0, rest.doubleValue()));
		}
		for (JsonNode pass : PythonValues.get(passes, "staircase").valueStream().toList()) {
			JsonNode reading = pass.path("resources").path("containers").path(container).path("workingSetMiB");
			JsonNode throughput = pass.path("k6").path("throughputRps");
			JsonNode rate = PythonText.truthy(throughput) ? throughput : pass.path("rateRps");
			if (!PythonValues.isNone(reading) && !PythonValues.isNone(rate)) {
				points.add(new RatePoint(rate.doubleValue(), reading.doubleValue()));
			}
		}
		return points;
	}

	/**
	 * A container's memory fit and the points it was fitted to.
	 *
	 * @param fit the fit
	 * @param points each reading: its rate and working set
	 * @param rates the rates above rest
	 */
	private record Fitted(MemoryFit fit, List<RatePoint> points, List<Double> rates) {

	}

}
