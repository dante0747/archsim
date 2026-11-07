package io.archsim.engine.yaml;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.AbstractConstruct;
import org.yaml.snakeyaml.constructor.Construct;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.CollectionNode;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.NodeId;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

/**
 * SnakeYAML's safe constructor, which builds only YAML's own types, made to build them as
 * PyYAML's safe constructor does where SnakeYAML differs: a mapping's merged keys first
 * and a key written twice in its first place ({@code flatten_mapping}); a scalar of an
 * explicit tag read, or refused, as PyYAML reads it ({@link PyYamlScalars}); a value of a
 * tag that expects another kind of node refused; and the values JSON has no kind for kept
 * as text (a timestamp as Python's {@code str()} writes it, {@code !!binary} as its
 * base64 text).
 */
final class PyYamlConstructor extends SafeConstructor {

	/**
	 * PyYAML's tag for a scalar written {@code =}, which its safe loader cannot build.
	 */
	static final Tag VALUE = new Tag("tag:yaml.org,2002:value");

	/** What PyYAML says it was doing when it refuses a mapping. */
	private static final String MAPPING = "while constructing a mapping";

	/**
	 * A constructor under these options.
	 * @param options the bounds of what is read
	 */
	PyYamlConstructor(LoaderOptions options) {
		super(options);
		this.yamlConstructors.put(Tag.INT, new Scalar(PyYamlScalars::integer));
		this.yamlConstructors.put(Tag.FLOAT, new Scalar(PyYamlScalars::decimal));
		this.yamlConstructors.put(Tag.BOOL, new Scalar(PyYamlScalars::bool));
		this.yamlConstructors.put(Tag.NULL, new Scalar(PyYamlScalars::none));
		this.yamlConstructors.put(Tag.STR, new Scalar(PyYamlScalars::text));
		this.yamlConstructors.put(Tag.TIMESTAMP, new Scalar(PythonTimestamps::text));
		this.yamlConstructors.put(Tag.BINARY, new Scalar(PyYamlScalars::base64));
		for (Tag tag : new Tag[] { Tag.SEQ, Tag.OMAP, Tag.PAIRS }) {
			this.yamlConstructors.put(tag,
					new KindChecked(Objects.requireNonNull(this.yamlConstructors.get(tag)), NodeId.sequence));
		}
		for (Tag tag : new Tag[] { Tag.MAP, Tag.SET }) {
			this.yamlConstructors.put(tag,
					new KindChecked(Objects.requireNonNull(this.yamlConstructors.get(tag)), NodeId.mapping));
		}
	}

	/**
	 * The value of a document's node.
	 * @param node the document, composed
	 * @return its value: maps, lists, text, numbers, booleans and {@code null}
	 */
	Object build(Node node) {
		return constructDocument(node);
	}

	@Override
	protected void flattenMapping(MappingNode node) {
		flattenMapping(node, false);
	}

	/**
	 * Merges a mapping's {@code <<} mappings into it as PyYAML does: the merged pairs
	 * before the mapping's own, a list of mappings taken from its last, and no pair
	 * removed, so a key keeps the first place it is written in and the last value it is
	 * given. A key that is a list or a mapping is refused, as Python cannot hash it; it
	 * is refused here, before any key is built, where PyYAML refuses it when it reaches
	 * it, so a mapping with another problem before it can report another.
	 * @param node the mapping
	 * @param forceStringKeys unused: PyYAML keeps a key's own type
	 * @throws InvalidYamlException for a key that is a list or a mapping
	 */
	@Override
	protected void flattenMapping(MappingNode node, boolean forceStringKeys) {
		List<NodeTuple> merged = new ArrayList<>();
		List<NodeTuple> own = new ArrayList<>();
		for (NodeTuple pair : node.getValue()) {
			Node key = pair.getKeyNode();
			if (key.getTag().equals(Tag.MERGE)) {
				merged.addAll(mergedPairs(pair.getValueNode()));
			}
			else {
				if (key.getTag().equals(VALUE)) {
					key.setTag(Tag.STR);
				}
				own.add(pair);
			}
		}
		merged.addAll(own);
		for (NodeTuple pair : merged) {
			if (pair.getKeyNode() instanceof CollectionNode<?> key) {
				throw InvalidYamlException.constructing(MAPPING, "found unhashable key", key.getStartMark());
			}
		}
		node.setValue(merged);
	}

	/**
	 * The pairs a {@code <<} key merges.
	 * @param value its value: a mapping, or a list of mappings
	 * @return their pairs, the list's last mapping's first
	 */
	private List<NodeTuple> mergedPairs(Node value) {
		if (value instanceof MappingNode mapping) {
			flattenMapping(mapping, false);
			return mapping.getValue();
		}
		if (!(value instanceof SequenceNode sequence)) {
			throw InvalidYamlException.constructing(MAPPING,
					"expected a mapping or list of mappings for merging, but found " + value.getNodeId(),
					value.getStartMark());
		}
		List<List<NodeTuple>> mappings = new ArrayList<>();
		for (Node item : sequence.getValue()) {
			if (!(item instanceof MappingNode mapping)) {
				throw InvalidYamlException.constructing(MAPPING,
						"expected a mapping for merging, but found " + item.getNodeId(), item.getStartMark());
			}
			flattenMapping(mapping, false);
			mappings.add(mapping.getValue());
		}
		Collections.reverse(mappings);
		List<NodeTuple> pairs = new ArrayList<>();
		mappings.forEach(pairs::addAll);
		return pairs;
	}

	/**
	 * A scalar's value, refused when the node is not a scalar, as PyYAML's
	 * {@code construct_scalar} refuses it, and a refusal of its text marked where it is.
	 */
	private final class Scalar extends AbstractConstruct {

		private final PyYamlScalars.Reading reading;

		Scalar(PyYamlScalars.Reading reading) {
			this.reading = reading;
		}

		@Override
		public @Nullable Object construct(Node node) {
			if (!(node instanceof ScalarNode scalar)) {
				throw InvalidYamlException.constructing(null, "expected a scalar node, but found " + node.getNodeId(),
						node.getStartMark());
			}
			try {
				return this.reading.read(constructScalar(scalar));
			}
			catch (InvalidYamlException ex) {
				throw ex.at(scalar.getStartMark());
			}
		}

	}

	/**
	 * SnakeYAML's construct of a collection, after checking the node is of the kind it
	 * expects, as PyYAML checks it.
	 */
	private static final class KindChecked implements Construct {

		private final Construct construct;

		private final NodeId kind;

		KindChecked(Construct construct, NodeId kind) {
			this.construct = construct;
			this.kind = kind;
		}

		@Override
		public Object construct(Node node) {
			check(node);
			return this.construct.construct(node);
		}

		@Override
		public void construct2ndStep(Node node, Object object) {
			check(node);
			this.construct.construct2ndStep(node, object);
		}

		private void check(Node node) {
			if (node.getNodeId() != this.kind) {
				throw InvalidYamlException.constructing(null,
						"expected a " + this.kind + " node, but found " + node.getNodeId(), node.getStartMark());
			}
		}

	}

}
