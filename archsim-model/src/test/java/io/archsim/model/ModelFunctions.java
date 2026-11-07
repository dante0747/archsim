package io.archsim.model;

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

import io.archsim.model.catalog.ComponentCatalog;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.distribution.Distribution;
import io.archsim.model.distribution.Empirical;
import io.archsim.model.document.ArchitectureDocument;
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
import io.archsim.parity.PythonError;

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
					(entry) -> DesignDocuments.normalize(DesignDocuments.parse(entry.input("doc")),
							StandardComponents.registry())),
			Map.entry("archsim_engine.scenarios.apply_overrides", ModelFunctions::applyOverrides),
			Map.entry("archsim_engine.document_json_schema", (entry) -> DesignDocuments.jsonSchema()),
			Map.entry("archsim_engine.registry.ComponentRegistry.catalog_document",
					(entry) -> ComponentCatalog.of(StandardComponents.registry())),
			Map.entry("archsim_engine.registry.ComponentRegistry.types",
					(entry) -> array(StandardComponents.registry().types().stream().map(NODES::stringNode).toList())),
			Map.entry("archsim_engine.distributions.Distribution.expected",
					(entry) -> NODES.numberNode(distribution(entry).expected())),
			Map.entry("archsim_engine.distributions.Distribution.describe",
					(entry) -> NODES.stringNode(distribution(entry).describe())),
			Map.entry("archsim_engine.distributions.Empirical.quantile",
					(entry) -> NODES
						.numberNode(((Empirical) distribution(entry)).quantile(entry.input("q").doubleValue()))),
			Map.entry("archsim_engine.pricing.parse_snapshot",
					(entry) -> snapshotJson(PriceSnapshot.parse(entry.input("data")))),
			Map.entry("archsim_engine.pricing.content_sha256",
					(entry) -> NODES.stringNode(PriceSnapshot.contentSha256(entry.input("data")))),
			Map.entry("archsim_engine.pricing.available",
					(entry) -> array(
							(entry.input("everyDate").booleanValue() ? PRICES.everyDate() : PRICES.newest()).stream()
								.map(RecordCodec::write)
								.toList())),
			Map.entry("archsim_engine.pricing.default_pricing", (entry) -> PRICES.defaultPricing()), Map
				.entry("archsim_engine.pricing.load_snapshot",
						(entry) -> PRICES.load(entry.input("provider").stringValue(), text(entry.input("region")),
								text(entry.input("date")), text(entry.input("sha256")))
							.toJson()),
			Map.entry("archsim_engine.pricing.snapshot_for",
					(entry) -> PRICES.forPricing(entry.input("pricing")).toJson()),
			Map.entry("archsim_engine.pricing.pinned",
					(entry) -> Objects.requireNonNullElse(PRICES.pinned(entry.input("pricing")), NODES.nullNode())),
			Map.entry("archsim_engine.pricing.unknown_families", ModelFunctions::unknownFamilies),
			Map.entry("archsim_engine.technology.TIER_NOTE", (entry) -> NODES.stringNode(ComponentCatalog.TIER_NOTE)),
			Map.entry("archsim_engine.version.SCHEMA_VERSION",
					(entry) -> NODES.stringNode(ArchitectureDocument.SCHEMA_VERSION)));

	private ModelFunctions() {
	}

	/** The Python functions ported here, by qualified name. */
	static Set<String> names() {
		return PORTED.keySet();
	}

	/**
	 * The Java counterpart of the entry's function on the entry's input. The exceptions
	 * the golden files record become the errors the Python engine raises in their place.
	 */
	static JsonNode call(GoldenEntry entry) {
		Function<GoldenEntry, JsonNode> counterpart = Objects.requireNonNull(PORTED.get(entry.fn()), entry.fn());
		try {
			return counterpart.apply(entry);
		}
		catch (UpgradeException ex) {
			throw new PythonError(error("MigrationError", ex), ex);
		}
		catch (PricingException ex) {
			throw new PythonError(error("PricingError", ex), ex);
		}
		catch (DocumentInvalidException ex) {
			ObjectNode json = error("DocumentInvalidError", ex);
			ex.issues().stream().map(Issue::toJson).forEach(json.putArray("issues")::add);
			throw new PythonError(json, ex);
		}
	}

	/** How the Python engine records an exception: its type and its message. */
	private static ObjectNode error(String type, RuntimeException exception) {
		return NODES.objectNode().put("type", type).put("message", exception.getMessage());
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
					array(types.stream().map((type) -> NODES.stringNode(type.json())).toList())));
		return output;
	}

	private static JsonNode applyOverrides(GoldenEntry entry) {
		List<ScenarioOverride> overrides = new ArrayList<>();
		entry.input("overrides")
			.forEach((override) -> overrides.add(RecordCodec.read(override, ScenarioOverride.class)));
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

	/** The families a snapshot does not know, of those asked about. */
	private static JsonNode unknownFamilies(GoldenEntry entry) {
		PriceSnapshot snapshot = snapshotOf(entry.input("snapshot"));
		List<String> unknown = snapshot.unknownFamilies(strings(entry.input("families")));
		return array(unknown.stream().map(NODES::stringNode).toList());
	}

	/** A snapshot back from what {@link #snapshotJson} records. */
	private static PriceSnapshot snapshotOf(JsonNode json) {
		Map<SkuCategory, List<Sku>> skus = new EnumMap<>(SkuCategory.class);
		for (SkuCategory category : SkuCategory.values()) {
			List<Sku> items = new ArrayList<>();
			json.path("skus").path(category.json()).forEach((sku) -> items.add(RecordCodec.read(sku, Sku.class)));
			skus.put(category, items);
		}
		return new PriceSnapshot(json.get("provider").stringValue(), text(json.get("region")),
				text(json.get("snapshotDate")), List.of(), json.get("currency").stringValue(),
				json.get("illustrative").booleanValue(), text(json.get("note")), skus, Map.of(),
				text(json.get("sha256")));
	}

	private static @Nullable String text(@Nullable JsonNode value) {
		return (value == null || value.isNull()) ? null : value.stringValue();
	}

	private static List<String> strings(JsonNode array) {
		List<String> out = new ArrayList<>();
		array.forEach((item) -> out.add(item.stringValue()));
		return out;
	}

	private static ArrayNode array(List<? extends JsonNode> items) {
		ArrayNode array = NODES.arrayNode();
		items.forEach(array::add);
		return array;
	}

}
