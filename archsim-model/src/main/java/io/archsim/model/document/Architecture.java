package io.archsim.model.document;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.OmitWhenDefault;

/**
 * The topology: what exists and how requests flow.
 *
 * @param nodes the components
 * @param edges the links between them
 * @param autoSize whether parts without a CPU, memory or storage size get the size they
 * need
 * @param host the one machine every part with a CPU size runs on; {@code null}: each its
 * own
 */
public record Architecture(@Default("[]") @MaxItems(200) List<NodeSpec> nodes,
		@Default("[]") @MaxItems(1000) List<EdgeSpec> edges, @Default("true") @Description(AUTO_SIZE) boolean autoSize,
		@OmitWhenDefault @Description(HOST) @Nullable HostSpec host) {

	static final String AUTO_SIZE = "Give parts with an empty CPU, memory or storage spec the size they need for "
			+ "the design's peak load (see sizing.py). false: an empty spec is not modelled.";

	static final String HOST = "The one machine every part with a CPU size runs on (docker compose on one VM, a "
			+ "benchmark on a laptop): their CPU work shares its physical cores. Empty: each part's vCPUs are its "
			+ "own (as before schema 1.7).";

	/**
	 * Copies the lists, so the architecture does not change.
	 */
	public Architecture {
		nodes = List.copyOf(nodes);
		edges = List.copyOf(edges);
	}

	/**
	 * The first node with this id.
	 * @param id the node's id
	 * @return the node; empty when there is none
	 */
	public Optional<NodeSpec> node(String id) {
		return this.nodes.stream().filter((node) -> node.id().equals(id)).findFirst();
	}
}
