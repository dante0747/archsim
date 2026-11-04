package io.archsim.engine.estimate;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.model.codec.BindingException;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * A design's parts and links as the estimate walks them. Where ids repeat it keeps the
 * Python engine's dictionary semantics: the last part with an id gives its type, label
 * and family, the first one that parses gives its config.
 */
final class EstimateGraph {

	private static final Comparator<EdgeSpec> CALL_ORDER = Comparator
		.comparingInt((EdgeSpec edge) -> edge.config().order())
		.thenComparing(EdgeSpec::id);

	private final Architecture architecture;

	private final Map<String, NodeSpec> nodes = new LinkedHashMap<>();

	private final Map<String, ComponentFamily> families = new LinkedHashMap<>();

	private final Map<String, Record> configs = new LinkedHashMap<>();

	private final Map<String, List<EdgeSpec>> calls = new LinkedHashMap<>();

	private EstimateGraph(Architecture architecture) {
		this.architecture = architecture;
	}

	/**
	 * The graph of a design.
	 * @param architecture the design's parts and links
	 * @param registry the component types
	 * @return the graph
	 */
	static EstimateGraph of(Architecture architecture, ComponentRegistry registry) {
		EstimateGraph graph = new EstimateGraph(architecture);
		for (NodeSpec node : architecture.nodes()) {
			graph.nodes.put(node.id(), node);
			graph.families.put(node.id(), ComponentFamily.of(node.type(), registry));
			if (!graph.configs.containsKey(node.id())) {
				registry.find(node.type())
					.flatMap((spec) -> parsed(spec, node))
					.ifPresent((config) -> graph.configs.put(node.id(), config));
			}
		}
		for (EdgeSpec edge : architecture.edges()) {
			graph.calls.computeIfAbsent(edge.source(), (source) -> new ArrayList<>()).add(edge);
		}
		graph.calls.values().forEach((edges) -> edges.sort(CALL_ORDER));
		return graph;
	}

	/**
	 * The design's parts and links.
	 * @return the architecture
	 */
	Architecture architecture() {
		return this.architecture;
	}

	/**
	 * The part with an id: the last one with it.
	 * @param id the part's id
	 * @return the part
	 */
	NodeSpec node(String id) {
		return Objects.requireNonNull(this.nodes.get(id), id);
	}

	/**
	 * A part's label, or its id when it has none.
	 * @param id the part's id
	 * @return the label
	 */
	String label(String id) {
		return node(id).name();
	}

	/**
	 * Whether a part is a client.
	 * @param id the part's id
	 * @return whether it sends traffic
	 */
	boolean isClient(String id) {
		return this.families.get(id) == ComponentFamily.TRAFFIC;
	}

	/**
	 * A part's parsed config.
	 * @param id the part's id
	 * @return its config; empty for an unknown type or a config that is not valid
	 */
	Optional<Record> config(String id) {
		return Optional.ofNullable(this.configs.get(id));
	}

	/**
	 * The links a part calls, in call order (their {@code order}, then their id).
	 * @param id the part's id
	 * @return its outgoing links
	 */
	List<EdgeSpec> calls(String id) {
		return this.calls.getOrDefault(id, List.of());
	}

	/**
	 * The parts in call order, callers before the parts they call (Kahn's algorithm from
	 * every part nothing calls, in the design's order); parts on a cycle are left out.
	 * @return the parts' ids; an id appears once for each part with it that nothing calls
	 */
	List<String> callOrder() {
		Map<String, Integer> callers = new LinkedHashMap<>();
		this.architecture.nodes().forEach((node) -> callers.put(node.id(), 0));
		for (EdgeSpec edge : this.architecture.edges()) {
			if (callers.containsKey(edge.target()) && callers.containsKey(edge.source())) {
				callers.merge(edge.target(), 1, Integer::sum);
			}
		}
		Deque<String> queue = new ArrayDeque<>();
		this.architecture.nodes()
			.stream()
			.filter((node) -> callers.getOrDefault(node.id(), 0) == 0)
			.forEach((node) -> queue.add(node.id()));
		List<String> order = new ArrayList<>();
		while (!queue.isEmpty()) {
			String id = queue.poll();
			order.add(id);
			for (EdgeSpec edge : calls(id)) {
				if (callers.containsKey(edge.target()) && callers.merge(edge.target(), -1, Integer::sum) == 0) {
					queue.add(edge.target());
				}
			}
		}
		return order;
	}

	/**
	 * The parts and links on a client's request path: everything the clients reach,
	 * except the work behind a message queue (its consumers run in the background).
	 * @return the parts and links on the path
	 */
	RequestPath requestPath() {
		Set<String> parts = new HashSet<>();
		Set<String> links = new HashSet<>();
		Deque<String> stack = new ArrayDeque<>();
		this.architecture.nodes()
			.stream()
			.filter((node) -> isClient(node.id()))
			.forEach((node) -> stack.push(node.id()));
		while (!stack.isEmpty()) {
			String id = stack.pop();
			if (!parts.add(id) || this.families.get(id) == ComponentFamily.MESSAGING) {
				continue;
			}
			for (EdgeSpec edge : calls(id)) {
				links.add(edge.id());
				stack.push(edge.target());
			}
		}
		return new RequestPath(Collections.unmodifiableSet(parts), Collections.unmodifiableSet(links));
	}

	/**
	 * A part's config, parsed.
	 * @param spec the part's type
	 * @param node the part
	 * @return its config record; empty when it is not valid (validation reports that)
	 */
	static Optional<Record> parsed(ComponentSpec<?> spec, NodeSpec node) {
		try {
			return Optional.of(spec.parseConfig(node.config()));
		}
		catch (BindingException ex) {
			// a config that is not valid is reported by validation
			return Optional.empty();
		}
	}

	/**
	 * What a client's requests reach.
	 *
	 * @param parts the parts on the path
	 * @param links the links on the path
	 */
	record RequestPath(Set<String> parts, Set<String> links) {
	}

}
