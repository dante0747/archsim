package io.archsim.benchmarks.baselines;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import tools.jackson.databind.JsonNode;

import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.queue.MessageQueueSpec;

/**
 * What one client request sends each part of a design: its reads and writes, from the
 * clients along the links, each link carrying its request types, a cache passing on its
 * misses only, and whether they reach the part through a queue, behind which no request
 * waits. The benchmark designs are trees, so a part has one caller. Made whole on
 * creation, and read-only after.
 */
final class RequestLoads {

	private final Map<String, JsonNode> parts = new LinkedHashMap<>();

	private final Map<String, List<JsonNode>> calls = new HashMap<>();

	private final Map<String, Load> loads = new HashMap<>();

	private final double missShare;

	/**
	 * The loads of a design's parts.
	 * @param design the design
	 * @param missShare the share of a cache's lookups that miss
	 */
	RequestLoads(JsonNode design, double missShare) {
		this.missShare = missShare;
		Set<String> called = new HashSet<>();
		design.at("/architecture/nodes").forEach((part) -> this.parts.put(id(part), part));
		for (JsonNode link : design.at("/architecture/edges")) {
			this.calls.computeIfAbsent(link.get("source").stringValue(), (source) -> new ArrayList<>()).add(link);
			called.add(target(link));
		}
		double reads = design.at("/workload/readRatio").doubleValue();
		Load whole = new Load(reads, 1 - reads, false);
		List<String> clients = this.parts.keySet().stream().filter((part) -> !called.contains(part)).toList();
		clients.forEach((client) -> send(client, whole));
	}

	/**
	 * The design's parts.
	 * @return the parts, in the design's order
	 */
	List<JsonNode> parts() {
		return List.copyOf(this.parts.values());
	}

	/**
	 * What one client request sends a part.
	 * @param part the part
	 * @return its load; none for a part no request reaches
	 */
	Load sentTo(JsonNode part) {
		return this.loads.getOrDefault(id(part), Load.NONE);
	}

	/**
	 * The links a part calls.
	 * @param part the part
	 * @return its outgoing links, in the design's order
	 */
	List<JsonNode> calls(JsonNode part) {
		return this.calls.getOrDefault(id(part), List.of());
	}

	/**
	 * The part a link reaches.
	 * @param link the link
	 * @return the part
	 */
	JsonNode calledBy(JsonNode link) {
		return part(target(link));
	}

	private JsonNode part(String id) {
		return Objects.requireNonNull(this.parts.get(id), id);
	}

	private void send(String id, Load load) {
		this.loads.merge(id, load, Load::plus);
		JsonNode part = part(id);
		for (JsonNode link : calls(part)) {
			send(target(link), load.over(link, part.get("type").stringValue(), this.missShare));
		}
	}

	private static String id(JsonNode part) {
		return part.get("id").stringValue();
	}

	private static String target(JsonNode link) {
		return link.get("target").stringValue();
	}

	/**
	 * What one client request sends a part.
	 *
	 * @param reads its reads
	 * @param writes its writes
	 * @param behindQueue whether they reach it through a queue
	 */
	record Load(double reads, double writes, boolean behindQueue) {

		/** The load of a part no request reaches. */
		static final Load NONE = new Load(0.0, 0.0, false);

		/**
		 * The operations one client request sends the part.
		 * @return its reads and writes
		 */
		double total() {
			return this.reads + this.writes;
		}

		private Load plus(Load other) {
			return new Load(this.reads + other.reads, this.writes + other.writes,
					this.behindQueue && other.behindQueue);
		}

		// what a link carries on: its request types, and of a cache's lookups the misses
		private Load over(JsonNode link, String sourceType, double missShare) {
			List<String> carried = link.at("/config/requestTypes").valueStream().map(JsonNode::stringValue).toList();
			double share = CacheSpec.TYPE.equals(sourceType) ? missShare : 1.0;
			return new Load(carried.contains("read") ? this.reads * share : 0.0,
					carried.contains("write") ? this.writes * share : 0.0,
					this.behindQueue || MessageQueueSpec.TYPE.equals(sourceType));
		}

	}

}
