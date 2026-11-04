package io.archsim.engine.sizing;

import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.pricing.PricingChoice;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.text.PythonText;

/**
 * Auto-sizing: the CPU, memory and storage each part needs for the design's own peak
 * load. An empty spec is auto: before a run, and in an analysis, the part gets the size
 * worked out here, so every caller simulates the same document the same way. A value set
 * on the part always wins. The rules follow what the simulation does with each spec, so
 * an auto-sized part is not the bottleneck at the peak it was sized for; each part also
 * records what it needs, beside the instance it gets.
 */
public final class AutoSizer {

	private static final String INSTANCE_TYPE = "instanceType";

	private final ComponentRegistry registry;

	private final PriceSnapshots prices;

	private final LoadEstimator estimator;

	/**
	 * An auto-sizer for designs of the given component types and price snapshots.
	 * @param registry the component types
	 * @param prices the price snapshots, whose instance types sizes snap to
	 */
	public AutoSizer(ComponentRegistry registry, PriceSnapshots prices) {
		this.registry = registry;
		this.prices = prices;
		this.estimator = new LoadEstimator(registry);
	}

	/**
	 * The auto size of every part that has resource specs.
	 * @param design the design, normalized (its configs in full) and its prices pinned
	 * @return each part's size, by its id, in the design's order
	 */
	public Map<String, PartSize> sizes(ArchitectureDocument design) {
		PartSizer sizer = new PartSizer(design, this.registry, PricingChoice.of(design.pricing(), this.prices),
				SizingLoads.of(design, this.estimator, this.registry));
		Map<String, PartSize> sizes = new LinkedHashMap<>();
		for (NodeSpec node : design.architecture().nodes()) {
			sizer.size(node).ifPresent((size) -> sizes.put(node.id(), size));
		}
		return sizes;
	}

	/**
	 * A design with every empty spec filled with its auto size, and the instance type
	 * chosen recorded, so cost and a re-run of the run's input use the same one.
	 * @param normalized the design as JSON, normalized and its prices pinned; not changed
	 * @return the filled copy and the sizes; no sizes when the design turns auto-sizing
	 * off
	 */
	public SizedDocument applyTo(ObjectNode normalized) {
		ObjectNode filled = normalized.deepCopy();
		JsonNode autoSize = filled.path("architecture").path("autoSize");
		if (!autoSize.isMissingNode() && !PythonText.truthy(autoSize)) {
			return new SizedDocument(filled, Map.of());
		}
		Map<String, PartSize> sizes = sizes(DesignDocuments.parse(filled));
		for (JsonNode node : filled.path("architecture").path("nodes")) {
			PartSize size = sizes.get(node.path("id").asString(""));
			if (size != null && node instanceof ObjectNode part) {
				fill(configOf(part), size);
			}
		}
		return new SizedDocument(filled, sizes);
	}

	private static void fill(ObjectNode config, PartSize size) {
		for (Spec spec : Spec.values()) {
			JsonNode set = config.path(spec.json());
			boolean empty = !set.isNumber() || !Double.isFinite(set.doubleValue());
			if (empty) {
				size.spec(spec).ifPresent((value) -> config.put(spec.json(), value));
			}
		}
		if (size.sku() != null && !PythonText.truthy(config.path(INSTANCE_TYPE))) {
			config.put(INSTANCE_TYPE, size.sku());
		}
	}

	private static ObjectNode configOf(ObjectNode node) {
		JsonNode config = node.path("config");
		return (config instanceof ObjectNode object) ? object : node.putObject("config");
	}

}
