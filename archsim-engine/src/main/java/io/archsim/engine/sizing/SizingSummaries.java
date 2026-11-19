package io.archsim.engine.sizing;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.cost.SizePricing;
import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.estimate.PartEstimate;
import io.archsim.engine.host.SiblingSpeed;
import io.archsim.engine.host.SiblingSpeeds;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.pricing.MachineKind;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.component.queue.MessageQueueSpec;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * Each part's sizing summary: the CPU, memory and storage it is simulated with and where
 * each came from (set on the part, auto-sized, or a scenario's override), its auto sizes
 * with how they were worked out, what it needs against what its instance has, the price
 * of the size used, and how its vCPUs counted.
 */
public final class SizingSummaries {

	/** The decimals a peak rate keeps. */
	private static final int RATE_DIGITS = 3;

	private final SizePricing pricing;

	private final SiblingSpeeds siblingSpeeds;

	/**
	 * Summarizes the sizes of designs made of a registry's types.
	 * @param registry the component types
	 * @param prices the price snapshots on this machine
	 */
	public SizingSummaries(ComponentRegistry registry, PriceSnapshots prices) {
		this.pricing = new SizePricing(registry, prices);
		this.siblingSpeeds = new SiblingSpeeds(prices);
	}

	/**
	 * The sizing summary of a design.
	 * @param design the design as drawn, for the specs set on its parts
	 * @param resolved the design as simulated, for the specs it is simulated with
	 * @param sizes each part's auto size
	 * @param estimate the instant estimate, for a service's typical memory
	 * @return the summary
	 */
	public SizingSummary of(ArchitectureDocument design, ArchitectureDocument resolved, Map<String, PartSize> sizes,
			InstantEstimate estimate) {
		Map<String, JsonNode> drawn = new HashMap<>();
		design.architecture().nodes().forEach((node) -> drawn.put(node.id(), node.config()));
		Map<String, PartEstimate> rows = new HashMap<>();
		estimate.parts().forEach((part) -> rows.put(part.id(), part));
		SizePricing.Prices prices = this.pricing.of(resolved);
		Map<String, SiblingSpeed> speeds = this.siblingSpeeds.asRun(design, resolved);
		Map<String, PartSummary> nodes = new LinkedHashMap<>();
		for (NodeSpec node : resolved.architecture().nodes()) {
			PartSize size = sizes.get(node.id());
			if (size != null) {
				nodes.put(node.id(), part(node, size, drawn.get(node.id()),
						new Inputs(prices, rows.get(node.id()), speeds.get(node.id()))));
			}
		}
		return new SizingSummary(resolved.architecture().autoSize(), Sizing.TARGET_CPU, nodes);
	}

	/**
	 * One part's summary.
	 * @param node the part as simulated
	 * @param size its auto size
	 * @param drawn its config as drawn; {@code null} when the design does not have it
	 * @param inputs the size prices and the part's estimate
	 * @return the summary
	 */
	private static PartSummary part(NodeSpec node, PartSize size, @Nullable JsonNode drawn, Inputs inputs) {
		Map<String, SpecValue> specs = specs(node, size, drawn);
		SizePricing.SizePrice price = inputs.prices().of(node);
		Map<String, Double> need = PartNeeds.of(size, inputs.estimate());
		Map<String, Double> rounded = null;
		Map<String, String> texts = null;
		if (!need.isEmpty()) {
			rounded = PartNeeds.rounded(need);
			String holder = (price.sku() != null && !price.sku().isEmpty()) ? price.sku() : holder(node);
			texts = NeedText.of(need, specs, holder, size.siblingSpeed());
		}
		SiblingSpeed speed = inputs.speed();
		return new PartSummary(specs, auto(size), PythonRound.round(size.peakRps(), RATE_DIGITS), why(size),
				perUse(node) ? null : price.pricePerHour(), price.sku(), rounded, texts, size.smallest(),
				size.skipped(), (speed != null) ? VcpuSummary.of(speed) : null);
	}

	/**
	 * The specs a part is simulated with, and where each came from: set on the part as
	 * drawn, its auto size, or else a scenario's override.
	 * @param node the part as simulated
	 * @param size its auto size
	 * @param drawn its config as drawn
	 * @return each spec it has, by its JSON name
	 */
	private static Map<String, SpecValue> specs(NodeSpec node, PartSize size, @Nullable JsonNode drawn) {
		Map<String, SpecValue> specs = new LinkedHashMap<>();
		for (Spec spec : Spec.values()) {
			JsonNode value = node.config().path(spec.json());
			if (value.isNull() || value.isMissingNode()) {
				continue;
			}
			JsonNode given = (drawn != null) ? drawn.path(spec.json()) : null;
			SpecSource source;
			if (given != null && !given.isNull() && !given.isMissingNode()) {
				source = SpecSource.SET;
			}
			else {
				boolean auto = size.spec(spec)
					.filter((sized) -> value.isNumber() && sized == value.doubleValue())
					.isPresent();
				source = auto ? SpecSource.AUTO : SpecSource.SCENARIO;
			}
			specs.put(spec.json(), new SpecValue(value, source));
		}
		return specs;
	}

	private static Map<String, @Nullable Double> auto(PartSize size) {
		Map<String, @Nullable Double> auto = new LinkedHashMap<>();
		for (Spec spec : Spec.values()) {
			auto.put(spec.json(), size.spec(spec).orElse(null));
		}
		return auto;
	}

	private static Map<String, String> why(PartSize size) {
		Map<String, String> why = new LinkedHashMap<>();
		size.why().forEach((spec, text) -> why.put(spec.json(), text));
		return why;
	}

	/**
	 * Whether a part is billed per use (requests, GB-seconds, capacity units), not per
	 * hour of its size.
	 * @param node the part
	 * @return whether it is a serverless function or a managed service
	 */
	private static boolean perUse(NodeSpec node) {
		JsonNode platform = node.config().path("platform");
		if (ServiceSpec.TYPE.equals(node.type()) && platform.isString()
				&& "serverless".equals(platform.stringValue())) {
			return true;
		}
		JsonNode technology = node.config().path("technology");
		return technology.isString() && MachineKind.MANAGED_TECHNOLOGIES.contains(technology.stringValue());
	}

	/**
	 * What holds a part's resources when it has no instance type.
	 * @param node the part
	 * @return a pod, a function instance, a replica, an instance, a node or a broker
	 */
	private static String holder(NodeSpec node) {
		return switch (node.type()) {
			case ServiceSpec.TYPE -> switch (node.config().path("platform").asString("")) {
				case "kubernetes" -> "each pod";
				case "serverless" -> "each function instance";
				default -> "each replica";
			};
			case CacheSpec.TYPE, LoadBalancerSpec.TYPE -> "each node";
			case MessageQueueSpec.TYPE -> "each broker";
			default -> "each instance";
		};
	}

	/**
	 * What a part's summary is worked out from beyond its size.
	 *
	 * @param prices the design's size prices
	 * @param estimate the part's estimate; {@code null} when the estimate has none
	 * @param speed how its vCPUs counted; {@code null} when the rule does not count them
	 */
	private record Inputs(SizePricing.Prices prices, @Nullable PartEstimate estimate, @Nullable SiblingSpeed speed) {
	}

}
