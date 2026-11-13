package io.archsim.benchmarks.designs;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * A link of a design: its latency each way, a constant or a distribution, and the request
 * types it carries.
 */
final class Edge {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private final ObjectNode edge;

	private Edge(ObjectNode edge) {
		this.edge = edge;
	}

	/**
	 * A link that carries reads and writes from a node's {@code out} port.
	 * @param id the link's id
	 * @param source the node it leaves
	 * @param target the node it reaches
	 * @param latency its latency each way (ms): a number or a distribution
	 * @return the link
	 */
	static Edge of(String id, String source, String target, JsonNode latency) {
		ObjectNode edge = NODES.objectNode()
			.put("id", id)
			.put("source", source)
			.put("sourcePort", "out")
			.put("target", target)
			.put("targetPort", "in");
		ObjectNode config = edge.putObject("config");
		config.putArray("requestTypes").add("read").add("write");
		config.set("latencyMs", latency.isObject() ? ((ObjectNode) latency).deepCopy()
				: NODES.objectNode().put("kind", "constant").set("value", latency));
		return new Edge(edge);
	}

	/**
	 * This link from another port of its source.
	 * @param port the port
	 * @return this link
	 */
	Edge fromPort(String port) {
		this.edge.put("sourcePort", port);
		return this;
	}

	/**
	 * This link carrying reads alone.
	 * @return this link
	 */
	Edge reads() {
		((ObjectNode) this.edge.get("config")).putArray("requestTypes").add("read");
		return this;
	}

	/**
	 * The link as JSON.
	 * @return the link
	 */
	ObjectNode json() {
		return this.edge;
	}

}
