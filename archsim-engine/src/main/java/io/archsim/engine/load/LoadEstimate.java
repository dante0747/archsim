package io.archsim.engine.load;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * The load each part and link carries, estimated without a run. A part whose load depends
 * on the run has none: its value is {@code null}.
 */
public final class LoadEstimate {

	private final Map<String, @Nullable NodeLoad> nodes;

	private final Map<String, @Nullable NodeLoad> edges;

	LoadEstimate(Map<String, @Nullable NodeLoad> nodes, Map<String, @Nullable NodeLoad> edges) {
		this.nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
		this.edges = Collections.unmodifiableMap(new LinkedHashMap<>(edges));
	}

	/**
	 * The load of a part.
	 * @param nodeId the part's id
	 * @return its load; empty when it depends on the run, or the design has no such part
	 */
	public Optional<NodeLoad> node(String nodeId) {
		return Optional.ofNullable(this.nodes.get(nodeId));
	}

	/**
	 * Every part's load, in the design's order.
	 * @return the loads by part id, {@code null} where a load depends on the run
	 */
	public Map<String, @Nullable NodeLoad> nodes() {
		return this.nodes;
	}

	/**
	 * The first attempts sent over each link a load crossed, in the order they were sent.
	 * @return the loads by link id, {@code null} where a load depends on the run
	 */
	public Map<String, @Nullable NodeLoad> edges() {
		return this.edges;
	}

}
