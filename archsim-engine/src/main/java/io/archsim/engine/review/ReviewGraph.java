package io.archsim.engine.review;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;

/**
 * A design as the review reads it: each part's settings over its type's defaults, read
 * leniently as the Python engine reads a design that may not be valid yet (ADR-0038), its
 * family, the links into and out of it between parts the design has, the parts a request
 * can reach, and the traffic sources. Of two parts with one id, the later counts, as in
 * the Python engine's dictionaries.
 */
final class ReviewGraph {

	/** A cache whose hit ratio is a fixed share of its reads. */
	static final String FIXED_RATIO = "fixed_ratio";

	/** The platform of a service that runs as serverless functions. */
	static final String SERVERLESS = "serverless";

	private final Architecture architecture;

	private final Map<String, NodeSpec> nodes = new HashMap<>();

	private final Map<String, LenientSettings> settings = new HashMap<>();

	private final Map<String, ComponentFamily> families = new HashMap<>();

	private final Map<String, List<EdgeSpec>> outs = new HashMap<>();

	private final Map<String, List<EdgeSpec>> ins = new HashMap<>();

	private final Set<String> reachable = new HashSet<>();

	private final List<NodeSpec> sources;

	ReviewGraph(Architecture architecture, ComponentRegistry registry) {
		this.architecture = architecture;
		for (NodeSpec node : architecture.nodes()) {
			this.nodes.put(node.id(), node);
			this.settings.put(node.id(),
					registry.find(node.type())
						.map((type) -> LenientSettings.overDefaults(type, node.config()))
						.orElseGet(() -> LenientSettings.of(node.config())));
			this.families.put(node.id(), ComponentFamily.of(node.type(), registry));
		}
		for (EdgeSpec edge : architecture.edges()) {
			if (has(edge.source()) && has(edge.target())) {
				this.outs.computeIfAbsent(edge.source(), (id) -> new ArrayList<>()).add(edge);
				this.ins.computeIfAbsent(edge.target(), (id) -> new ArrayList<>()).add(edge);
			}
		}
		this.sources = architecture.nodes()
			.stream()
			.filter((node) -> family(node.id()) == ComponentFamily.TRAFFIC)
			.toList();
		reachFromSources();
	}

	/**
	 * The design's architecture.
	 * @return its parts and links, every one of them
	 */
	Architecture architecture() {
		return this.architecture;
	}

	/**
	 * Whether the design has a part.
	 * @param id the part's id
	 * @return whether a part has that id
	 */
	boolean has(String id) {
		return this.nodes.containsKey(id);
	}

	/**
	 * How the review names a part: its label, else its id.
	 * @param id the part's id
	 * @return its name; the id for a part the design does not have
	 */
	String label(String id) {
		NodeSpec node = this.nodes.get(id);
		return (node != null) ? node.name() : id;
	}

	/**
	 * A part's settings over its type's defaults.
	 * @param id a part the design has
	 * @return its settings
	 */
	LenientSettings settings(String id) {
		return Objects.requireNonNull(this.settings.get(id), id);
	}

	/**
	 * Whether a part runs as serverless functions.
	 * @param id a part the design has
	 * @return whether its platform is serverless
	 */
	boolean serverless(String id) {
		return settings(id).is("platform", SERVERLESS);
	}

	/**
	 * Whether a cache has a hit model. A hit model that counts as false is the fixed
	 * ratio, as the Python engine reads {@code hitModel or "fixed_ratio"}.
	 * @param id a part the design has
	 * @param model the hit model
	 * @return whether the part has that model
	 */
	boolean hitModelIs(String id, String model) {
		LenientSettings cache = settings(id);
		return cache.truthy("hitModel", false) ? cache.is("hitModel", model) : FIXED_RATIO.equals(model);
	}

	/**
	 * A part's family.
	 * @param id a part the design has
	 * @return its family
	 */
	ComponentFamily family(String id) {
		return Objects.requireNonNull(this.families.get(id), id);
	}

	/**
	 * The links out of a part, to parts the design has.
	 * @param id the part's id
	 * @return its links, in the design's order
	 */
	List<EdgeSpec> outs(String id) {
		return this.outs.getOrDefault(id, List.of());
	}

	/**
	 * The links into a part, from parts the design has.
	 * @param id the part's id
	 * @return its links, in the design's order
	 */
	List<EdgeSpec> ins(String id) {
		return this.ins.getOrDefault(id, List.of());
	}

	/**
	 * Whether a request from a traffic source can reach a part.
	 * @param id the part's id
	 * @return whether it is on a request path
	 */
	boolean onPath(String id) {
		return this.reachable.contains(id);
	}

	/**
	 * The traffic sources.
	 * @return the parts of the traffic family, in the design's order
	 */
	List<NodeSpec> sources() {
		return this.sources;
	}

	private void reachFromSources() {
		Deque<String> stack = new ArrayDeque<>();
		this.sources.forEach((source) -> stack.push(source.id()));
		while (!stack.isEmpty()) {
			String id = stack.pop();
			if (this.reachable.add(id)) {
				outs(id).forEach((edge) -> stack.push(edge.target()));
			}
		}
	}

}
