package io.archsim.model;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Empirical;
import io.archsim.model.document.ScenarioOverride;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.pricing.PricingException;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.SkuCategory;
import io.archsim.model.scenario.Scenarios;
import io.archsim.model.upgrade.UpgradeException;
import io.archsim.model.upgrade.Upgraded;
import io.archsim.model.upgrade.Upgrades;
import io.archsim.model.validation.DocumentValidator;
import io.archsim.model.validation.ValidationResult;
import io.archsim.parity.GoldenEntry;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

/**
 * The Python engine's functions this module ports, each as the Java call that replaces
 * it, taking a golden entry's input and giving its output as the golden file records it.
 */
final class ModelFunctions {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final DocumentValidator VALIDATOR = DocumentValidator.standard();

	private static final PriceSnapshots PRICES = PriceSnapshots.shipped();

	private static final Map<String, Function<GoldenEntry, JsonNode>> PORTED = Map.ofEntries(
			Map.entry("archsim_engine.migrations.migrate_document", ModelFunctions::migrateDocument),
			Map.entry("archsim_engine.scenarios.load_document", ModelFunctions::loadDocument),
			Map.entry("archsim_engine.scenarios.simulation_input", ModelFunctions::simulationInput),
			Map.entry("archsim_engine.validation.validate_document", ModelFunctions::validateDocument),
			Map.entry("archsim_engine.scenarios.normalize_document",
					entry -> DesignDocuments.normalize(DesignDocuments.parse(entry.input("doc")),
							StandardComponents.registry())),
			Map.entry("archsim_engine.scenarios.apply_overrides", ModelFunctions::applyOverrides),
			Map.entry("archsim_engine.document_json_schema", entry -> DesignDocuments.jsonSchema()),
			Map.entry("archsim_engine.distributions.Distribution.expected",
					entry -> NODES.numberNode(distribution(entry).expected())),
			Map.entry("archsim_engine.distributions.Distribution.describe",
					entry -> NODES.stringNode(distribution(entry).describe())),
			Map.entry("archsim_engine.distributions.Empirical.quantile",
					entry -> NODES
						.numberNode(((Empirical) distribution(entry)).quantile(entry.input("q").doubleValue()))),
			Map.entry("archsim_engine.pricing.parse_snapshot",
					entry -> snapshotJson(PriceSnapshot.parse(entry.input("data")))),
			Map.entry("archsim_engine.pricing.content_sha256",
					entry -> NODES.stringNode(PriceSnapshot.contentSha256(entry.input("data")))),
			Map.entry("archsim_engine.pricing.available",
					entry -> array(PRICES.available(entry.input("everyDate").booleanValue())
						.stream()
						.map(RecordCodec::write)
						.toList())),
			Map.entry("archsim_engine.pricing.default_pricing", entry -> PRICES.defaultPricing()),
			Map.entry("archsim_engine.pricing.load_snapshot",
					entry -> PRICES
						.load(entry.input("provider").stringValue(), text(entry.input("region")),
								text(entry.input("date")), text(entry.input("sha256")))
						.toJson()),
			Map.entry("archsim_engine.pricing.snapshot_for",
					entry -> PRICES.forPricing(entry.input("pricing")).toJson()),
			Map.entry("archsim_engine.pricing.pinned",
					entry -> Objects.requireNonNullElse(PRICES.pinned(entry.input("pricing")), NODES.nullNode())),
			Map.entry("archsim_engine.pricing.unknown_families",
					entry -> array(snapshotOf(entry.input("snapshot")).unknownFamilies(strings(entry.input("families")))
						.stream()
						.map(NODES::stringNode)
						.toList())));

	private ModelFunctions() {
	}

	/** The Python functions ported here, by qualified name. */
	static Set<String> names() {
		return PORTED.keySet();
	}

	/** The Java counterpart of the entry's function on the entry's input. */
	static JsonNode call(GoldenEntry entry) {
		return Objects.requireNonNull(PORTED.get(entry.fn()), entry.fn()).apply(entry);
	}

	/**
	 * How the Python engine records an exception: its type, its message, and a document's
	 * issues.
	 */
	static JsonNode error(RuntimeException e) {
		ObjectNode json = NODES.objectNode();
		switch (e) {
			case UpgradeException upgrade -> json.put("type", "MigrationError").put("message", upgrade.getMessage());
			case PricingException pricing -> json.put("type", "PricingError").put("message", pricing.getMessage());
			case DocumentInvalidException invalid -> {
				json.put("type", "DocumentInvalidError").put("message", invalid.getMessage());
				invalid.issues().stream().map(Issue::toJson).forEach(json.putArray("issues")::add);
			}
			default -> throw e;
		}
		return json;
	}

	private static JsonNode migrateDocument(GoldenEntry entry) {
		Upgraded upgraded = Upgrades.toCurrent(entry.input("raw"));
		ObjectNode output = NODES.objectNode();
		output.set("document", upgraded.document());
		upgraded.notes().forEach(output.putArray("notes")::add);
		return output;
	}

	private static JsonNode loadDocument(GoldenEntry entry) {
		return DesignDocuments.toJson(DesignDocuments.load(entry.input("raw")));
	}

	private static JsonNode simulationInput(GoldenEntry entry) {
		return DesignDocuments.simulationInput(DesignDocuments.parse(entry.input("resolved")));
	}

	/**
	 * {@code ValidationResult.to_dict()} and the request types each link carries, sorted.
	 */
	private static JsonNode validateDocument(GoldenEntry entry) {
		JsonNode bound = entry.input("maxAmplification");
		ValidationResult result = VALIDATOR.validate(DesignDocuments.parse(entry.input("doc")),
				bound.isNull() ? null : bound.doubleValue());
		ObjectNode output = result.toJson();
		ObjectNode flows = output.putObject("edgeFlows");
		result.edgeFlows()
			.forEach((edge, types) -> flows.set(edge,
					array(types.stream().map(t -> NODES.stringNode(t.json())).toList())));
		return output;
	}

	private static JsonNode applyOverrides(GoldenEntry entry) {
		List<ScenarioOverride> overrides = new ArrayList<>();
		entry.input("overrides").forEach(o -> overrides.add(RecordCodec.read(o, ScenarioOverride.class)));
		return Scenarios.applyOverrides((ObjectNode) entry.input("docDict"), overrides,
				entry.input("scenarioName").stringValue());
	}

	private static Distribution distribution(GoldenEntry entry) {
		return RecordCodec.read(entry.input("d"), Distribution.class);
	}

	/**
	 * A snapshot in full, as the exporter records it: {@code to_json()} plus its types
	 * and rates.
	 */
	private static JsonNode snapshotJson(PriceSnapshot snapshot) {
		ObjectNode json = snapshot.toJson();
		ObjectNode skus = json.putObject("skus");
		snapshot.skus()
			.forEach((category, items) -> skus.set(category.json(),
					array(items.stream().map(RecordCodec::write).toList())));
		ObjectNode rates = json.putObject("rates");
		snapshot.rates().forEach(rates::put);
		return json;
	}

	/** A snapshot back from what {@link #snapshotJson} records. */
	private static PriceSnapshot snapshotOf(JsonNode json) {
		Map<SkuCategory, List<Sku>> skus = new EnumMap<>(SkuCategory.class);
		for (SkuCategory category : SkuCategory.values()) {
			List<Sku> items = new ArrayList<>();
			json.path("skus").path(category.json()).forEach(s -> items.add(RecordCodec.read(s, Sku.class)));
			skus.put(category, items);
		}
		return new PriceSnapshot(json.get("provider").stringValue(), text(json.get("region")),
				text(json.get("snapshotDate")), List.of(), json.get("currency").stringValue(),
				json.get("illustrative").booleanValue(), text(json.get("note")), skus, Map.of(),
				text(json.get("sha256")));
	}

	private static @Nullable String text(@Nullable JsonNode value) {
		return value == null || value.isNull() ? null : value.stringValue();
	}

	private static List<String> strings(JsonNode array) {
		List<String> out = new ArrayList<>();
		array.forEach(item -> out.add(item.stringValue()));
		return out;
	}

	private static ArrayNode array(List<? extends JsonNode> items) {
		ArrayNode array = NODES.arrayNode();
		items.forEach(array::add);
		return array;
	}

}
