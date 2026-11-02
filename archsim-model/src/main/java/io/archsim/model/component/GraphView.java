package io.archsim.model.component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

/**
 * The design as component rules see it: its nodes and their parsed configs, the valid
 * links into and out of each node, the workload, and the request types that reach each
 * node.
 *
 * @param nodes every node by id, in the document's order (a repeated id keeps the last
 * node)
 * @param configs each node's parsed config, for the nodes whose config is valid
 * @param outEdges each node's valid outgoing links, in the document's order
 * @param inEdges each node's valid incoming links, in the document's order
 * @param workload the document's workload
 * @param incomingTypes the request types that reach each node; empty when they were not
 * worked out (a cycle, or not exactly one client)
 */
public record GraphView(Map<String, NodeSpec> nodes, Map<String, Record> configs, Map<String, List<EdgeSpec>> outEdges,
		Map<String, List<EdgeSpec>> inEdges, Workload workload, Map<String, Set<RequestType>> incomingTypes) {

	public GraphView {
		nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		configs = Collections.unmodifiableMap(new LinkedHashMap<>(configs));
		outEdges = Collections.unmodifiableMap(new LinkedHashMap<>(outEdges));
		inEdges = Collections.unmodifiableMap(new LinkedHashMap<>(inEdges));
		incomingTypes = Collections.unmodifiableMap(new LinkedHashMap<>(incomingTypes));
	}

	/**
	 * The same view with the request types that reach each node.
	 * @param types the request types that reach each node, by node id
	 * @return a view that knows them
	 */
	public GraphView withIncomingTypes(Map<String, Set<RequestType>> types) {
		return new GraphView(this.nodes, this.configs, this.outEdges, this.inEdges, this.workload, types);
	}

	public List<EdgeSpec> outEdges(String nodeId) {
		return this.outEdges.getOrDefault(nodeId, List.of());
	}

	public List<EdgeSpec> inEdges(String nodeId) {
		return this.inEdges.getOrDefault(nodeId, List.of());
	}

	public Optional<NodeSpec> node(String id) {
		return Optional.ofNullable(this.nodes.get(id));
	}

	/**
	 * The ids of the nodes of a component type, in the document's order.
	 * @param type the component type's name
	 * @return the ids of its nodes
	 */
	public List<String> idsOfType(String type) {
		return this.nodes.values().stream().filter((node) -> node.type().equals(type)).map(NodeSpec::id).toList();
	}

	/**
	 * How messages name a node: its label, or its id; an id with no node is its own name.
	 * @param nodeId the node's id
	 * @return the node's name in messages
	 */
	public String name(String nodeId) {
		NodeSpec node = this.nodes.get(nodeId);
		return (node != null) ? node.name() : nodeId;
	}

}
