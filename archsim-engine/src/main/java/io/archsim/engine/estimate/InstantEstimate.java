package io.archsim.engine.estimate;

import java.util.List;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;

/**
 * What queueing formulas predict for a design at its peak, without simulating it.
 *
 * @param method how it was estimated
 * @param atRps the peak load it is estimated at, in requests per second
 * @param latencyMs the latency over every request type and client; {@code null} when a
 * part on the path is overloaded, or the load on some parts depends on the run
 * @param p95Approximate always {@code true}: the p95 is a rough approximation
 * @param busiest the part that uses most of its slots or CPU; {@code null} when no part
 * has a known capacity
 * @param overloaded whether a part cannot keep up
 * @param saturationRps the load the design saturates at, about
 * @param summary the estimate in a line
 * @param paths each client's requests of each kind, with their mean latency
 * @param breakdown where an average request's time goes, the largest first
 * @param parts each part's estimate, in the design's order
 * @param notes what the estimate leaves out, and its caveats
 */
public record InstantEstimate(String method, @Nullable Double atRps, @Nullable Latency latencyMs,
		boolean p95Approximate, @Nullable Busiest busiest, boolean overloaded, @Nullable Double saturationRps,
		String summary, List<ClientPath> paths, List<TimeShare> breakdown, List<PartEstimate> parts,
		List<String> notes) {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	/**
	 * Copies the lists, so the record does not change.
	 */
	public InstantEstimate {
		paths = List.copyOf(paths);
		breakdown = List.copyOf(breakdown);
		parts = List.copyOf(parts);
		notes = List.copyOf(notes);
	}

	/**
	 * The estimate as the analysis writes it. {@code RecordCodec} writes each piece; the
	 * parts come in two shapes (a part the formulas cannot estimate has a note where the
	 * others have their figures), which the codec's unions, told apart by a tag, cannot
	 * write as one list, so the estimate is put together here.
	 * @return the JSON object
	 */
	public ObjectNode toJson() {
		ObjectNode json = NODES.objectNode().put("method", this.method);
		json.put("atRps", this.atRps);
		json.set("latencyMs", (this.latencyMs != null) ? RecordCodec.write(this.latencyMs) : NODES.nullNode());
		json.put("p95Approximate", this.p95Approximate);
		json.set("busiest", (this.busiest != null) ? RecordCodec.write(this.busiest) : NODES.nullNode());
		json.put("overloaded", this.overloaded);
		json.put("saturationRps", this.saturationRps);
		json.put("summary", this.summary);
		json.set("paths", records(this.paths));
		json.set("breakdown", records(this.breakdown));
		json.set("parts", records(this.parts.stream().map((part) -> (Record) part).toList()));
		ArrayNode remarks = json.putArray("notes");
		this.notes.forEach(remarks::add);
		return json;
	}

	private static JsonNode records(List<? extends Record> records) {
		ArrayNode array = NODES.arrayNode();
		records.forEach((item) -> array.add(RecordCodec.write(item)));
		return array;
	}

}
