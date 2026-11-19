package io.archsim.model.document;

import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.Description;
import io.archsim.model.codec.Gt;
import io.archsim.model.codec.Le;
import io.archsim.model.codec.MaxItems;
import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.codec.Ui;

/**
 * The topology: what exists and how requests flow.
 *
 * @param nodes the components
 * @param edges the links between them
 * @param autoSize whether parts without a CPU, memory or storage size get the size they
 * need
 * @param host the one machine every part with a CPU size runs on; {@code null}: each its
 * own
 * @param siblingSpeed the sibling speed of the vCPUs of every service, database and cache
 * that sets none of its own (schema 1.11): 1 for whole cores; {@code null}: each part's
 * instance family and platform say, else hyperthread pairs at the default speed
 */
public record Architecture(@Default("[]") @MaxItems(200) List<NodeSpec> nodes,
		@Default("[]") @MaxItems(1000) List<EdgeSpec> edges,
		@Default("true") @Description(Descriptions.ARCHITECTURE_AUTO_SIZE) boolean autoSize,
		@OmitWhenDefault @Description(Descriptions.ARCHITECTURE_HOST) @Nullable HostSpec host,
		@OmitWhenDefault @Description(Descriptions.ARCHITECTURE_SIBLING_SPEED) @Ui(label = "vCPU sibling speed",
				group = Descriptions.ARCHITECTURE_MACHINES_GROUP,
				step = 0.05) @Gt(0) @Le(1) @Nullable Double siblingSpeed) {

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
