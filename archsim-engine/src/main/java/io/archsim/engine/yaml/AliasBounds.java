package io.archsim.engine.yaml;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.SequenceNode;

/**
 * How far a YAML document expands once its aliases are followed, measured on its composed
 * nodes before any value is built: a few hundred bytes of nested aliases ("billion
 * laughs") would otherwise build millions of values. Real files do use aliases (Compose's
 * {@code <<: *defaults}), so they are allowed within bounds.
 */
final class AliasBounds {

	/** The most values a document may expand to. */
	static final int MAX_EXPANDED_NODES = 200_000;

	/** The deepest nesting accepted. */
	static final int MAX_DEPTH = 100;

	/** What a document nesting too deeply is refused with. */
	static final String TOO_DEEP = "the YAML nests deeper than " + MAX_DEPTH + " levels";

	private final IdentityHashMap<Node, Integer> sizes = new IdentityHashMap<>();

	private final Set<Node> inProgress = Collections.newSetFromMap(new IdentityHashMap<>());

	private AliasBounds() {
	}

	/**
	 * Checks a document against the bounds.
	 * @param document the document's root node
	 * @throws YamlValueException when it nests too deeply, contains a recursive alias or
	 * expands to too many values
	 */
	static void check(Node document) {
		new AliasBounds().size(document, 0);
	}

	/**
	 * The values a node expands to, each shared node measured once.
	 * @param node the node
	 * @param depth how deep it is
	 * @return the number of values it expands to, with itself
	 */
	private int size(Node node, int depth) {
		if (depth > MAX_DEPTH) {
			throw new YamlValueException(TOO_DEEP);
		}
		Integer known = this.sizes.get(node);
		if (known != null) {
			return known;
		}
		if (!this.inProgress.add(node)) {
			throw new YamlValueException("the YAML contains a recursive alias");
		}
		int total = 1 + children(node, depth + 1);
		this.inProgress.remove(node);
		if (total > MAX_EXPANDED_NODES) {
			throw new YamlValueException("the YAML expands to more than 200,000 values through aliases");
		}
		this.sizes.put(node, total);
		return total;
	}

	/**
	 * The values a node's children expand to, stopping once past the bound.
	 * @param node the node
	 * @param depth how deep its children are
	 * @return their values
	 */
	private int children(Node node, int depth) {
		int total = 0;
		if (node instanceof SequenceNode sequence) {
			for (Node child : sequence.getValue()) {
				total += size(child, depth);
				if (total >= MAX_EXPANDED_NODES) {
					break;
				}
			}
		}
		else if (node instanceof MappingNode mapping) {
			for (NodeTuple entry : mapping.getValue()) {
				total += size(entry.getKeyNode(), depth) + size(entry.getValueNode(), depth);
				if (total >= MAX_EXPANDED_NODES) {
					break;
				}
			}
		}
		return total;
	}

}
