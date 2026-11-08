package io.archsim.engine.reporting;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.provenance.Input;
import io.archsim.engine.provenance.InputProvenances;
import io.archsim.engine.report.ModelNotes;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.technology.PartTechnology;
import io.archsim.engine.technology.TechnologyTiers;
import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.Tier;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.CalibrationRecord;
import io.archsim.model.document.NodeSpec;

/**
 * A report's {@code assumptions}: what the model leaves out, each part's technology tier,
 * every input with where it came from, the design's calibration, what each part's type
 * models and does not, and the design's warnings.
 */
final class Assumptions {

	/** At most this many experimental parts are named in the note about them. */
	private static final int NAMED = 5;

	/** What the "experimental" badge says, within a sentence. */
	private static final String TIER_NOTE = ComponentCatalog.TIER_NOTE.substring(0, 1).toLowerCase(Locale.ROOT)
			+ ComponentCatalog.TIER_NOTE.substring(1);

	private final TechnologyTiers tiers;

	/** What each type models and does not, by type: the editor's catalog's lists. */
	private final Map<String, ObjectNode> modeled = new HashMap<>();

	Assumptions(ComponentRegistry registry) {
		this.tiers = new TechnologyTiers(registry);
		for (JsonNode entry : ComponentCatalog.of(registry).get("components")) {
			ObjectNode lists = Figures.NODES.objectNode();
			lists.set("modeled", entry.get("modeled"));
			lists.set("notModeled", entry.get("notModeled"));
			this.modeled.put(entry.get("type").stringValue(), lists);
		}
	}

	/**
	 * The assumptions of a run.
	 * @param prepared the run as it was prepared
	 * @param inputs each input of what was simulated, with where its value came from
	 * @return the assumptions
	 */
	ObjectNode write(PreparedRun prepared, List<Input> inputs) {
		ArchitectureDocument resolved = prepared.resolved();
		List<PartTechnology> technologies = this.tiers.of(resolved.architecture().nodes());
		ObjectNode assumptions = Figures.NODES.objectNode();
		ArrayNode notes = assumptions.putArray("modelNotes");
		ModelNotes.all().forEach(notes::add);
		tierNotes(technologies).forEach(notes::add);
		assumptions.set("technologies", records(technologies));
		assumptions.set("userInputs", records(inputs));
		ObjectNode counts = assumptions.putObject("provenanceCounts");
		InputProvenances.counts(inputs).forEach((kind, count) -> counts.put(kind.json(), count));
		CalibrationRecord calibration = resolved.calibration();
		assumptions.set("calibration",
				(calibration != null) ? RecordCodec.write(calibration) : Figures.NODES.nullNode());
		ObjectNode components = assumptions.putObject("components");
		resolved.architecture()
			.nodes()
			.stream()
			.map(NodeSpec::type)
			.distinct()
			.sorted()
			.forEach((type) -> components.set(type, Objects.requireNonNull(this.modeled.get(type)).deepCopy()));
		ArrayNode warnings = assumptions.putArray("validationWarnings");
		prepared.validation().warnings().forEach((warning) -> warnings.add(warning.toJson()));
		return assumptions;
	}

	/**
	 * The note naming the parts whose technology is experimental.
	 * @param technologies each part's technology and tier
	 * @return one note naming the first five, and how many more; none when every
	 * technology is core
	 */
	static List<String> tierNotes(List<PartTechnology> technologies) {
		List<String> names = technologies.stream()
			.filter((technology) -> technology.tier() == Tier.EXPERIMENTAL)
			.map(PartTechnology::label)
			.toList();
		if (names.isEmpty()) {
			return List.of();
		}
		String shown = String.join(", ", names.subList(0, Math.min(NAMED, names.size())));
		if (names.size() > NAMED) {
			shown += " and " + (names.size() - NAMED) + " more";
		}
		return List.of("Experimental technologies (" + shown + "): " + TIER_NOTE);
	}

	private static ArrayNode records(List<? extends Record> records) {
		ArrayNode array = Figures.NODES.arrayNode();
		records.forEach((record) -> array.add(RecordCodec.write(record)));
		return array;
	}

}
