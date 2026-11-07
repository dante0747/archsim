package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.ReplaySummary;
import io.archsim.engine.calibration.fit.Fit;
import io.archsim.engine.calibration.fit.FitKind;
import io.archsim.engine.calibration.fit.Histogram;
import io.archsim.engine.calibration.fit.LatencyFits;
import io.archsim.engine.calibration.mapping.MappingException;
import io.archsim.engine.calibration.mapping.MappingLoader;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.prometheus.LatencyUnit;
import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.engine.calibration.prometheus.PrometheusSeries;
import io.archsim.engine.calibration.prometheus.Reduction;
import io.archsim.model.codec.RecordCodec;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.PythonError;

/**
 * Calibration's deterministic parts: the mapping, a range, Prometheus series, latency
 * fits and the replay summary, which the golden files record.
 */
final class CalibrationFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.calibration.mapping.load_mapping", CalibrationFunctions::loadMapping),
			Map.entry("archsim_engine.calibration.apply.parse_range", CalibrationFunctions::parseRange),
			Map.entry("archsim_engine.calibration.prometheus.histogram", CalibrationFunctions::histogram),
			Map.entry("archsim_engine.calibration.prometheus.reduce", CalibrationFunctions::reduce),
			Map.entry("archsim_engine.calibration.fit.fit_histogram", CalibrationFunctions::fitHistogram),
			Map.entry("archsim_engine.calibration.fit.fit_samples", CalibrationFunctions::fitSamples),
			Map.entry("archsim_engine.calibration.apply.replay_summary", CalibrationFunctions::replaySummary));

	private CalibrationFunctions() {
	}

	/**
	 * The mapping, as pydantic dumps it by alias; an unusable one becomes the Python
	 * engine's {@code MappingError}.
	 */
	private static JsonNode loadMapping(GoldenEntry entry) {
		JsonNode source = entry.input("source");
		try {
			return RecordCodec
				.write(source.isString() ? MappingLoader.load(source.stringValue()) : MappingLoader.load(source));
		}
		catch (MappingException ex) {
			throw error("MappingError", ex);
		}
	}

	private static JsonNode parseRange(GoldenEntry entry) {
		try {
			return RecordCodec
				.write(TimeRange.parse(entry.input("text").stringValue(), entry.input("step").doubleValue()));
		}
		catch (IllegalArgumentException ex) {
			throw error("ValueError", ex);
		}
	}

	private static JsonNode histogram(GoldenEntry entry) {
		LatencyUnit unit = "s".equals(entry.input("unit").stringValue()) ? LatencyUnit.SECONDS
				: LatencyUnit.MILLISECONDS;
		try {
			return histogramJson(PrometheusSeries.histogram(entry.input("result"), unit));
		}
		catch (PrometheusException ex) {
			throw error("PrometheusError", ex);
		}
	}

	/**
	 * {@code reduce} takes the mean for any word but {@code max}, {@code min} and
	 * {@code last}.
	 */
	private static JsonNode reduce(GoldenEntry entry) {
		Reduction how = switch (entry.input("how").stringValue()) {
			case "max" -> Reduction.MAX;
			case "min" -> Reduction.MIN;
			case "last" -> Reduction.LAST;
			default -> Reduction.MEAN;
		};
		try {
			return GoldenJson.number(PrometheusSeries.reduce(entry.input("result"), how));
		}
		catch (PrometheusException ex) {
			throw error("PrometheusError", ex);
		}
	}

	private static JsonNode fitHistogram(GoldenEntry entry) {
		JsonNode recorded = entry.input("h");
		Histogram histogram = new Histogram(numbers(recorded.path("bounds")), numbers(recorded.path("cumulative")),
				recorded.path("total").doubleValue());
		try {
			return fitJson(LatencyFits.fitHistogram(histogram, kind(entry)));
		}
		catch (IllegalArgumentException ex) {
			throw error("ValueError", ex);
		}
	}

	private static JsonNode fitSamples(GoldenEntry entry) {
		try {
			return fitJson(LatencyFits.fitSamples(numbers(entry.input("samples")), kind(entry)));
		}
		catch (IllegalArgumentException ex) {
			throw error("ValueError", ex);
		}
	}

	private static JsonNode replaySummary(GoldenEntry entry) {
		JsonNode record = entry.input("record");
		return GoldenJson.orNone(ReplaySummary.of(record.isNull() ? null : record).map(GoldenJson::text));
	}

	/** Python's {@code FitKind}: anything but {@code empirical} fits a lognormal. */
	private static FitKind kind(GoldenEntry entry) {
		return "empirical".equals(entry.input("kind").stringValue()) ? FitKind.EMPIRICAL : FitKind.LOGNORMAL;
	}

	/** The dataclass as the golden encoder writes it, its float-keyed dict as pairs. */
	private static JsonNode fitJson(Fit fit) {
		ObjectNode json = GoldenJson.NODES.objectNode();
		json.set("distribution", fit.distribution());
		json.set("error", (fit.error() != null) ? GoldenJson.number(fit.error()) : GoldenJson.NODES.nullNode());
		ArrayNode observed = json.putArray("observed");
		fit.observed().forEach((quantile, value) -> observed.addArray().add(quantile).add(value));
		json.put("count", fit.count());
		ArrayNode notes = json.putArray("notes");
		fit.notes().forEach(notes::add);
		return json;
	}

	private static JsonNode histogramJson(Histogram histogram) {
		ObjectNode json = GoldenJson.NODES.objectNode();
		json.set("bounds", GoldenJson.numbers(histogram.bounds()));
		json.set("cumulative", GoldenJson.numbers(histogram.cumulative()));
		return json.put("total", histogram.total());
	}

	private static List<Double> numbers(JsonNode array) {
		List<Double> numbers = new ArrayList<>();
		array.forEach((number) -> numbers.add(number.doubleValue()));
		return numbers;
	}

	private static PythonError error(String type, RuntimeException ex) {
		return new PythonError(GoldenJson.NODES.objectNode().put("message", ex.getMessage()).put("type", type), ex);
	}

}
