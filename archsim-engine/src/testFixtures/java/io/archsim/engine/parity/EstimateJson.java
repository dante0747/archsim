package io.archsim.engine.parity;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.estimate.Busiest;
import io.archsim.engine.estimate.ClientPath;
import io.archsim.engine.estimate.EstimatedPart;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.Latency;
import io.archsim.engine.estimate.PartEstimate;
import io.archsim.engine.estimate.TimeShare;
import io.archsim.engine.estimate.UnestimatedPart;
import io.archsim.model.codec.RecordCodec;

/**
 * An instant estimate read back from the JSON an analysis writes, the Java's or the
 * frozen engine's.
 */
public final class EstimateJson {

	private EstimateJson() {
	}

	/**
	 * The estimate a JSON object holds: the parts the formulas could not estimate are
	 * those with a note.
	 * @param json the estimate's JSON
	 * @return the estimate
	 */
	public static InstantEstimate read(JsonNode json) {
		List<PartEstimate> parts = json.path("parts").valueStream().map(EstimateJson::part).toList();
		List<ClientPath> paths = records(json.path("paths"), ClientPath.class);
		List<TimeShare> breakdown = records(json.path("breakdown"), TimeShare.class);
		List<String> notes = json.path("notes").valueStream().map(JsonNode::asString).toList();
		return new InstantEstimate(json.path("method").asString(), number(json.path("atRps")),
				json.path("latencyMs").isNull() ? null : RecordCodec.read(json.path("latencyMs"), Latency.class),
				json.path("p95Approximate").asBoolean(),
				json.path("busiest").isNull() ? null : RecordCodec.read(json.path("busiest"), Busiest.class),
				json.path("overloaded").asBoolean(), number(json.path("saturationRps")),
				json.path("summary").asString(), paths, breakdown, parts, notes);
	}

	private static PartEstimate part(JsonNode part) {
		return part.has("note") ? RecordCodec.read(part, UnestimatedPart.class)
				: RecordCodec.read(part, EstimatedPart.class);
	}

	private static <T> List<T> records(JsonNode array, Class<T> type) {
		return array.valueStream().map((item) -> RecordCodec.read(item, type)).toList();
	}

	// a figure an estimate may lack: its peak, the load it saturates at
	private static @Nullable Double number(JsonNode value) {
		return value.isNumber() ? Double.valueOf(value.doubleValue()) : null;
	}

}
