package io.archsim.engine.reporting;

import java.util.HashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;

/**
 * How a report names the parts: by label, or by id when a part has no label.
 */
final class Labels {

	private final Map<String, String> byId = new HashMap<>();

	Labels(ArchitectureDocument design) {
		for (NodeSpec node : design.architecture().nodes()) {
			this.byId.put(node.id(), name(node));
		}
	}

	/**
	 * A part's name.
	 * @param node the part
	 * @return its label, or its id when it has none
	 */
	static String name(NodeSpec node) {
		String label = node.label();
		return (label == null || label.isEmpty()) ? node.id() : label;
	}

	/**
	 * The name of a part by its id.
	 * @param id the part's id
	 * @return the part's name, or the id itself for a part the design lacks
	 */
	String name(String id) {
		return this.byId.getOrDefault(id, id);
	}

	/**
	 * The name of a part by its id, if there is one.
	 * @param id the part's id; {@code null} for none
	 * @return the part's name, the id itself for a part the design lacks, {@code null}
	 * for none
	 */
	@Nullable String of(@Nullable String id) {
		return (id != null) ? name(id) : null;
	}

}
