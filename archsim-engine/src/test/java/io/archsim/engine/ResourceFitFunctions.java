package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.resources.CpuFit;
import io.archsim.engine.calibration.resources.CpuFits;
import io.archsim.engine.calibration.resources.CurveExtension;
import io.archsim.engine.calibration.resources.CurveExtensions;
import io.archsim.engine.calibration.resources.LoadCurve;
import io.archsim.engine.calibration.resources.LoadCurves;
import io.archsim.engine.calibration.resources.MemoryFit;
import io.archsim.engine.calibration.resources.MemoryFits;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.PythonError;

/**
 * Calibration's CPU and memory fits, which the golden files record, each dataclass as the
 * golden encoder writes it.
 */
final class ResourceFitFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.of(
			"archsim_engine.calibration.resources.fit_cpu_share", ResourceFitFunctions::fitCpuShare,
			"archsim_engine.calibration.resources.fit_load_curve", ResourceFitFunctions::fitLoadCurve,
			"archsim_engine.calibration.resources.extend_load_curve", ResourceFitFunctions::extendLoadCurve,
			"archsim_engine.calibration.resources.fit_memory", ResourceFitFunctions::fitMemory);

	private ResourceFitFunctions() {
	}

	private static JsonNode fitCpuShare(GoldenEntry entry) {
		return orNone(CpuFits.fitShare(points(entry.input("points")), entry.input("serviceMs").doubleValue(),
				entry.input("replicas").doubleValue()), ResourceFitFunctions::cpuFitJson);
	}

	private static JsonNode fitLoadCurve(GoldenEntry entry) {
		JsonNode reference = entry.input("referenceMs");
		return orNone(LoadCurves.fit(points(entry.input("points")), reference.isNull() ? null : reference.doubleValue(),
				entry.input("maxBins").intValue()), ResourceFitFunctions::curveJson);
	}

	private static JsonNode extendLoadCurve(GoldenEntry entry) {
		CurveExtensions.Bounds bounds = new CurveExtensions.Bounds(entry.input("minBusy").doubleValue(),
				entry.input("baseBusy").doubleValue(), entry.input("t").doubleValue());
		return orNone(CurveExtensions.extend(points(entry.input("passes")), entry.input("cores").doubleValue(), bounds),
				ResourceFitFunctions::extensionJson);
	}

	/**
	 * {@code fit_memory}; fitting against neither is the Python engine's
	 * {@code ValueError}.
	 */
	private static JsonNode fitMemory(GoldenEntry entry) {
		try {
			return orNone(MemoryFits.fit(numbers(entry.input("workingSetMb")), nullableNumbers(entry.input("rate")),
					nullableNumbers(entry.input("held"))), ResourceFitFunctions::memoryJson);
		}
		catch (IllegalArgumentException ex) {
			throw new PythonError(
					GoldenJson.NODES.objectNode().put("message", ex.getMessage()).put("type", "ValueError"), ex);
		}
	}

	private static JsonNode cpuFitJson(CpuFit fit) {
		ObjectNode json = GoldenJson.NODES.objectNode()
			.put("share", fit.share())
			.put("cpuMs", fit.cpuMs())
			.put("serviceMs", fit.serviceMs())
			.put("capped", fit.capped())
			.put("points", fit.points());
		json.set("error", nullable(fit.error()));
		json.set("curve", (fit.curve() != null) ? curveJson(fit.curve()) : GoldenJson.NODES.nullNode());
		ArrayNode notes = json.putArray("notes");
		fit.notes().forEach(notes::add);
		return json;
	}

	private static JsonNode curveJson(LoadCurve curve) {
		ObjectNode json = GoldenJson.NODES.objectNode().put("referenceMs", curve.referenceMs());
		json.set("points", pairs(curve.points()));
		json.set("measured", pairs(curve.measured()));
		json.set("error", nullable(curve.error()));
		return json;
	}

	private static JsonNode extensionJson(CurveExtension extension) {
		ObjectNode json = GoldenJson.NODES.objectNode()
			.put("topRps", extension.topRps())
			.put("topMs", extension.topMs())
			.put("busy", extension.busy())
			.put("slope", extension.slope())
			.put("reason", extension.reason());
		json.set("error", nullable(extension.error()));
		json.set("points", pairs(extension.points()));
		json.set("factors", pairs(extension.factors()));
		return json;
	}

	private static JsonNode memoryJson(MemoryFit fit) {
		ObjectNode json = GoldenJson.NODES.objectNode()
			.put("baselineMb", fit.baselineMb())
			.put("perRpsMb", fit.perRpsMb())
			.put("perRequestMb", fit.perRequestMb())
			.put("points", fit.points());
		json.set("error", nullable(fit.error()));
		ArrayNode against = json.putArray("against");
		fit.against().forEach(against::add);
		ArrayNode notes = json.putArray("notes");
		fit.notes().forEach(notes::add);
		return json;
	}

	private static <T> JsonNode orNone(Optional<T> value, Function<T, JsonNode> json) {
		return value.map(json).orElse(GoldenJson.NODES.nullNode());
	}

	private static JsonNode nullable(@Nullable Double number) {
		return (number != null) ? GoldenJson.number(number) : GoldenJson.NODES.nullNode();
	}

	private static ArrayNode pairs(List<RatePoint> points) {
		ArrayNode array = GoldenJson.NODES.arrayNode();
		points.forEach((point) -> array.addArray().add(point.rate()).add(point.value()));
		return array;
	}

	private static List<RatePoint> points(JsonNode pairs) {
		List<RatePoint> points = new ArrayList<>();
		pairs.forEach((pair) -> points.add(new RatePoint(pair.get(0).doubleValue(), pair.get(1).doubleValue())));
		return points;
	}

	private static List<Double> numbers(JsonNode array) {
		List<Double> numbers = new ArrayList<>();
		array.forEach((number) -> numbers.add(number.doubleValue()));
		return numbers;
	}

	private static @Nullable List<Double> nullableNumbers(JsonNode array) {
		return array.isNull() ? null : numbers(array);
	}

}
