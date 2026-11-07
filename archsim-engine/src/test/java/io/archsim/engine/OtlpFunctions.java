package io.archsim.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.otlp.CpuTime;
import io.archsim.engine.calibration.otlp.OtlpMapping;
import io.archsim.engine.calibration.otlp.OtlpMappings;
import io.archsim.engine.calibration.otlp.OtlpSpans;
import io.archsim.engine.calibration.otlp.Span;
import io.archsim.engine.calibration.otlp.TraceSummaries;
import io.archsim.engine.calibration.otlp.TraceSummary;
import io.archsim.parity.GoldenEntry;
import io.archsim.parity.PythonError;

/**
 * Calibration from OpenTelemetry traces: the spans of an export, their summary and the
 * OTLP mapping, which the golden files record.
 */
final class OtlpFunctions {

	static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.of(
			"archsim_engine.calibration.otlp.parse_otlp", OtlpFunctions::parseOtlp,
			"archsim_engine.calibration.otlp.summarize", OtlpFunctions::summarize,
			"archsim_engine.calibration.otlp.load_otlp_mapping", OtlpFunctions::loadOtlpMapping);

	private OtlpFunctions() {
	}

	private static JsonNode parseOtlp(GoldenEntry entry) {
		ArrayNode spans = GoldenJson.NODES.arrayNode();
		OtlpSpans.parse(entry.input("data")).forEach((span) -> spans.add(spanJson(span)));
		return spans;
	}

	private static JsonNode summarize(GoldenEntry entry) {
		List<Span> spans = new ArrayList<>();
		entry.input("spans").forEach((span) -> spans.add(span(span)));
		JsonNode cpuTime = entry.input("cpuTime");
		TraceSummary summary = TraceSummaries.summarize(spans,
				cpuTime.isNull() ? null : new CpuTime(cpuTime.get(0).stringValue(), cpuTime.get(1).doubleValue()));
		return summaryJson(summary);
	}

	/**
	 * The mapping as a dict; what is wrong with it is the Python engine's
	 * {@code ValueError}.
	 */
	private static JsonNode loadOtlpMapping(GoldenEntry entry) {
		try {
			return OtlpMappings.load(entry.input("source"))
				.map(OtlpFunctions::mappingJson)
				.orElse(GoldenJson.NODES.nullNode());
		}
		catch (IllegalArgumentException ex) {
			throw new PythonError(
					GoldenJson.NODES.objectNode().put("message", ex.getMessage()).put("type", "ValueError"), ex);
		}
	}

	private static JsonNode spanJson(Span span) {
		ObjectNode json = GoldenJson.NODES.objectNode()
			.put("traceId", span.traceId())
			.put("spanId", span.spanId())
			.put("parentId", span.parentId())
			.put("service", span.service())
			.put("name", span.name())
			.put("kind", span.kind())
			.put("startNs", span.startNs())
			.put("endNs", span.endNs());
		json.set("attrs", span.attrs());
		return json;
	}

	/** A span from the dataclass as the golden encoder writes it. */
	private static Span span(JsonNode json) {
		return new Span(json.path("traceId").stringValue(), json.path("spanId").stringValue(),
				GoldenJson.readNullableText(json.path("parentId")), json.path("service").stringValue(),
				json.path("name").stringValue(), json.path("kind").stringValue(), json.path("startNs").longValue(),
				json.path("endNs").longValue(), (ObjectNode) json.path("attrs"));
	}

	private static JsonNode summaryJson(TraceSummary summary) {
		ObjectNode json = GoldenJson.NODES.objectNode()
			.put("startNs", summary.startNs())
			.put("endNs", summary.endNs())
			.put("spans", summary.spans())
			.put("parentLoops", summary.parentLoops());
		json.set("services", lists(summary.services()));
		json.set("dbReads", lists(summary.dbReads()));
		json.set("dbWrites", lists(summary.dbWrites()));
		json.set("serverMs", lists(summary.serverMs()));
		json.set("roots", GoldenJson.numbers(summary.roots()));
		ArrayNode calls = json.putArray("calls");
		summary.calls()
			.forEach((call, count) -> calls.addArray()
				.add(GoldenJson.NODES.arrayNode().add(call.caller()).add(call.callee()))
				.add(count));
		ObjectNode cpu = json.putObject("cpu");
		summary.cpu().forEach((service, samples) -> {
			ArrayNode pairs = cpu.putArray(service);
			samples.forEach((sample) -> pairs.addArray().add(sample.cpuMs()).add(sample.ownMs()));
		});
		return json;
	}

	private static ObjectNode lists(Map<String, List<Double>> lists) {
		ObjectNode json = GoldenJson.NODES.objectNode();
		lists.forEach((key, values) -> json.set(key, GoldenJson.numbers(values)));
		return json;
	}

	private static JsonNode mappingJson(OtlpMapping mapping) {
		ObjectNode json = GoldenJson.NODES.objectNode();
		ObjectNode services = json.putObject("services");
		mapping.services().forEach(services::put);
		ObjectNode databases = json.putObject("databases");
		mapping.databases().forEach(databases::put);
		if (mapping.cpuTime() != null) {
			json.putObject("cpuTime")
				.put("attribute", mapping.cpuTime().attribute())
				.put("unit", mapping.cpuTime().unit().json());
		}
		return json;
	}

}
