package io.archsim.model.validation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.GraphView;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.issue.Issue;

/**
 * What one validation has worked out so far, shared by its checks: the nodes by id, the
 * link ids, the parsed configs and the valid links, which the first checks fill in and
 * the later ones read, and the issues found, in the order the checks found them.
 */
final class ValidationContext {

	private final ArchitectureDocument document;

	private final ComponentRegistry registry;

	private final List<Issue> issues = new ArrayList<>();

	private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();

	private final Set<String> edgeIds = new HashSet<>();

	private final Map<String, Record> configs = new LinkedHashMap<>();

	private final List<EdgeSpec> validEdges = new ArrayList<>();

	private final Map<String, List<EdgeSpec>> outEdges = new LinkedHashMap<>();

	private final Map<String, List<EdgeSpec>> inEdges = new LinkedHashMap<>();

	/**
	 * Starts validating a document.
	 * @param document the design to check
	 * @param registry the component types its nodes may use
	 */
	ValidationContext(ArchitectureDocument document, ComponentRegistry registry) {
		this.document = document;
		this.registry = registry;
	}

	ArchitectureDocument document() {
		return this.document;
	}

	/**
	 * The nodes as the document lists them, a repeated id included.
	 * @return every node, in the document's order
	 */
	List<NodeSpec> declaredNodes() {
		return this.document.architecture().nodes();
	}

	/**
	 * The links as the document lists them, valid or not.
	 * @return every link, in the document's order
	 */
	List<EdgeSpec> declaredEdges() {
		return this.document.architecture().edges();
	}

	/**
	 * The component type a node names.
	 * @param node the node whose type to look up
	 * @return its type, or empty when no component type has that name
	 */
	Optional<ComponentSpec<?>> spec(NodeSpec node) {
		return this.registry.find(node.type());
	}

	/**
	 * Whether a component type of this name exists.
	 * @param type the name a node gives its type
	 * @return {@code true} when the registry knows it
	 */
	boolean isKnownType(String type) {
		return this.registry.contains(type);
	}

	/**
	 * The names of every component type, for a hint that lists them.
	 * @return the known type names
	 */
	List<String> knownTypes() {
		return this.registry.types();
	}

	/**
	 * Whether a node generates load: a client of a known type.
	 * @param node the node to look at
	 * @return {@code true} for a traffic source, {@code false} for any other part or an
	 * unknown type
	 */
	boolean isTrafficSource(NodeSpec node) {
		return spec(node).map(ComponentSpec::isTrafficSource).orElse(false);
	}

	/**
	 * The nodes that generate load.
	 * @return the traffic sources, in the document's order
	 */
	List<NodeSpec> trafficSources() {
		return declaredNodes().stream().filter(this::isTrafficSource).toList();
	}

	void report(Issue issue) {
		this.issues.add(issue);
	}

	List<Issue> issues() {
		return this.issues;
	}

	/**
	 * Records a node under its id; a later node with the same id takes its place.
	 * @param node the node to record
	 */
	void index(NodeSpec node) {
		this.nodes.put(node.id(), node);
	}

	boolean hasNode(String id) {
		return this.nodes.containsKey(id);
	}

	/**
	 * The node with this id, if there is one.
	 * @param id the node's id
	 * @return the node, or {@code null} when no node has the id
	 */
	@Nullable NodeSpec node(String id) {
		return this.nodes.get(id);
	}

	/**
	 * The node with this id, which the caller knows exists.
	 * @param id the node's id
	 * @return the node
	 */
	NodeSpec requireNode(String id) {
		return Objects.requireNonNull(this.nodes.get(id));
	}

	/**
	 * How issues name a node that exists.
	 * @param id the node's id
	 * @return the node's name
	 */
	String nameOf(String id) {
		return requireNode(id).name();
	}

	/**
	 * The ids of the nodes, each once.
	 * @return the ids, in the order they first appear in the document
	 */
	List<String> nodeIds() {
		return List.copyOf(this.nodes.keySet());
	}

	void addEdgeId(String id) {
		this.edgeIds.add(id);
	}

	boolean hasEdge(String id) {
		return this.edgeIds.contains(id);
	}

	void putConfig(String nodeId, Record config) {
		this.configs.put(nodeId, config);
	}

	/**
	 * A node's parsed config.
	 * @param nodeId the node's id
	 * @return its config, or {@code null} when its type is unknown or its config invalid
	 */
	@Nullable Record config(String nodeId) {
		return this.configs.get(nodeId);
	}

	Map<String, Record> configs() {
		return this.configs;
	}

	/**
	 * Records a link that joins two nodes of known types on valid ports.
	 * @param edge the valid link
	 */
	void addValidEdge(EdgeSpec edge) {
		this.validEdges.add(edge);
		this.outEdges.computeIfAbsent(edge.source(), (source) -> new ArrayList<>()).add(edge);
		this.inEdges.computeIfAbsent(edge.target(), (target) -> new ArrayList<>()).add(edge);
	}

	List<EdgeSpec> validEdges() {
		return this.validEdges;
	}

	/**
	 * The valid links that leave a node.
	 * @param nodeId the node's id
	 * @return its outgoing links, in the document's order
	 */
	List<EdgeSpec> outEdges(String nodeId) {
		return this.outEdges.getOrDefault(nodeId, List.of());
	}

	/**
	 * The valid links that reach a node.
	 * @param nodeId the node's id
	 * @return its incoming links, in the document's order
	 */
	List<EdgeSpec> inEdges(String nodeId) {
		return this.inEdges.getOrDefault(nodeId, List.of());
	}

	/**
	 * The valid links by the node they leave, for the call graph.
	 * @return each node's outgoing links, read-only
	 */
	Map<String, List<EdgeSpec>> outEdgesByNode() {
		return Collections.unmodifiableMap(this.outEdges);
	}

	/**
	 * The design as component rules see it, before request types are routed.
	 * @return the nodes, configs, valid links and workload, with no incoming types
	 */
	GraphView graphView() {
		return new GraphView(this.nodes, this.configs, this.outEdges, this.inEdges, this.document.workload(), Map.of());
	}

}
