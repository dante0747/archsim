package io.archsim.engine.provenance;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeConfig;
import io.archsim.model.document.EdgeSpec;

/**
 * The input of each link, its network latency: illustrative when it is the default or
 * what the link's source type suggests, assumed otherwise, unless the document records
 * it.
 */
final class EdgeInputs {

	/** The link setting listed as an input. */
	private static final String LATENCY = "latencyMs";

	/** What the inspector calls it. */
	private static final String LATENCY_LABEL = "Network latency (one way)";

	/** A link's settings when none is written, as JSON. */
	private static final ObjectNode DEFAULTS = RecordCodec
		.write(RecordCodec.read(JsonNodeFactory.instance.objectNode(), EdgeConfig.class));

	private final ComponentFacts facts;

	EdgeInputs(ComponentFacts facts) {
		this.facts = facts;
	}

	/**
	 * The link's input.
	 * @param document the design
	 * @param edge the link
	 * @return its latency, with its value and provenance
	 */
	Input of(ArchitectureDocument document, EdgeSpec edge) {
		JsonNode value = RecordCodec.write(edge.config()).path(LATENCY);
		Provenance provenance = Recorded.of(document.provenance().get(InputPaths.edge(edge.id(), LATENCY)), value)
			.orClassify(() -> classify(document, edge.id(), value));
		return new Input(InputScope.EDGE, edge.id(), edge.source() + " → " + edge.target(), LATENCY, LATENCY_LABEL,
				edge.config().latencyMs().describe(), provenance);
	}

	/**
	 * Where the latency came from, the document recording nothing that holds. The link
	 * with this id that comes first in the design stands for every link with it, as in
	 * the Python engine.
	 * @param document the design
	 * @param id the link's id
	 * @param value the latency, as JSON
	 * @return the default, else the source type's suggestion, else assumed
	 */
	private Provenance classify(ArchitectureDocument document, String id, JsonNode value) {
		if (Values.isNone(value) || Values.equal(value, DEFAULTS.path(LATENCY))) {
			return Provenance.archSimDefault();
		}
		return document.architecture()
			.edges()
			.stream()
			.filter((edge) -> edge.id().equals(id))
			.findFirst()
			.flatMap((edge) -> document.node(edge.source()))
			.flatMap((node) -> this.facts.type(node.type()))
			.filter((type) -> type.linkDefault(LATENCY).filter((latency) -> Values.equal(latency, value)).isPresent())
			.map((type) -> Provenance.illustrative(type.displayName() + " link default"))
			.orElseGet(Provenance::assumed);
	}

}
