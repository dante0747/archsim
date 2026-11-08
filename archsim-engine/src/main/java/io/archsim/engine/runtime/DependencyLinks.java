package io.archsim.engine.runtime;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.archsim.model.document.RequestType;

/**
 * A part's outgoing links that carry each kind of request, in the order it calls them: by
 * their order, then their id. Each kind's links are listed once, when first asked for.
 */
public final class DependencyLinks {

	private final SimContext context;

	private final String nodeId;

	private final Map<RequestType, List<EdgeRuntime>> byOperation = new EnumMap<>(RequestType.class);

	/**
	 * A part's links.
	 * @param context the run
	 * @param nodeId the part's id
	 */
	public DependencyLinks(SimContext context, String nodeId) {
		this.context = context;
		this.nodeId = nodeId;
	}

	/**
	 * The links that carry a kind of request, in the order the part calls them.
	 * @param operation the kind of request
	 * @return the links, possibly none
	 */
	public List<EdgeRuntime> carrying(RequestType operation) {
		return this.byOperation.computeIfAbsent(operation, (kind) -> this.context.outEdges(this.nodeId, "out")
			.stream()
			.filter((edge) -> edge.carries(kind))
			.sorted(Comparator.comparingInt((EdgeRuntime edge) -> edge.config().order()).thenComparing(EdgeRuntime::id))
			.toList());
	}

}
