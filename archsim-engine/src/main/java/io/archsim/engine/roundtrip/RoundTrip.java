package io.archsim.engine.roundtrip;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.JsonEnum;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeConfig;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;

/**
 * What ArchSim's exports carry so an import gives the design back: each part's outgoing
 * links as {@code target[/port][:types][!flag...]}, comma separated, with the flags
 * {@code on-miss} and {@code carry-on} for the cache-aside link settings; and the parts
 * nothing deploys (clients, Kubernetes clusters), each with its settings and links.
 */
public final class RoundTrip {

	/** The types of the parts no export deploys. */
	private static final Set<String> NOT_DEPLOYED = Set.of("traffic_source", "kubernetes_cluster");

	/** The port a link leaves from unless it says otherwise. */
	private static final String DEFAULT_PORT = "out";

	/** What a link carries unless it says otherwise. */
	private static final List<RequestType> EVERY_REQUEST_TYPE = List.of(RequestType.READ, RequestType.WRITE);

	/**
	 * The container resources an export writes for a service with no CPU or memory set.
	 */
	private static final ObjectNode PLACEHOLDER_RESOURCES = placeholder();

	private RoundTrip() {
	}

	/**
	 * Outgoing links, as an export writes them.
	 * @param links a part's outgoing links, in order
	 * @return {@code target[/port][:types][!flag...]} for each, comma separated; empty
	 * for none
	 */
	public static String calls(List<EdgeSpec> links) {
		return links.stream().map(RoundTrip::call).collect(Collectors.joining(","));
	}

	/**
	 * The parts no export deploys (clients, Kubernetes clusters), with their outgoing
	 * links.
	 * @param document the design
	 * @return {@code {id, type, label, config, calls}} for each, in order, the config and
	 * calls left out when there are none
	 */
	public static ArrayNode parts(ArchitectureDocument document) {
		List<EdgeSpec> links = document.architecture().edges();
		ArrayNode parts = JsonNodeFactory.instance.arrayNode();
		for (NodeSpec node : document.architecture().nodes()) {
			if (!NOT_DEPLOYED.contains(node.type())) {
				continue;
			}
			ObjectNode part = parts.addObject().put("id", node.id()).put("type", node.type()).put("label", node.name());
			if (!node.config().isEmpty()) {
				part.set("config", node.config());
			}
			String calls = calls(links.stream().filter((link) -> link.source().equals(node.id())).toList());
			if (!calls.isEmpty()) {
				part.put("calls", calls);
			}
		}
		return parts;
	}

	/**
	 * The container resources the Kubernetes export writes for a service with no CPU or
	 * memory set. An import skips exactly these, and only in ArchSim's own manifests:
	 * anywhere else they are someone's real requests.
	 * @return {@code {requests: {cpu, memory}, limits: {memory}}}, a copy
	 */
	public static ObjectNode placeholderResources() {
		return PLACEHOLDER_RESOURCES.deepCopy();
	}

	private static String call(EdgeSpec link) {
		EdgeConfig config = link.config();
		StringBuilder call = new StringBuilder(link.target());
		String port = link.sourcePort();
		if (!port.isEmpty() && !port.equals(DEFAULT_PORT)) {
			call.append('/').append(port);
		}
		// a link's config keeps its request types sorted
		if (!config.requestTypes().equals(EVERY_REQUEST_TYPE)) {
			call.append(':')
				.append(config.requestTypes().stream().map(JsonEnum::json).collect(Collectors.joining("+")));
		}
		if (config.readsOnMiss()) {
			call.append("!on-miss");
		}
		if (config.continueOnFailure()) {
			call.append("!carry-on");
		}
		return call.toString();
	}

	private static ObjectNode placeholder() {
		ObjectNode resources = JsonNodeFactory.instance.objectNode();
		resources.putObject("requests").put("cpu", "500m").put("memory", "512Mi");
		resources.putObject("limits").put("memory", "1Gi");
		return resources;
	}

}
