package io.archsim.engine.host;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.pricing.Machine;
import io.archsim.engine.pricing.MachineSize;
import io.archsim.engine.pricing.PricingChoice;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.Resources;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.database.DatabaseSpec;
import io.archsim.model.component.service.Platform;
import io.archsim.model.component.service.ServiceSpec;
import io.archsim.model.document.Architecture;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.model.pricing.Sku;
import io.archsim.model.pricing.WholeCoreFamilies;

/**
 * How the vCPUs of each service, database and cache count (schema 1.11, ADR-0091): a part
 * with 2 or more vCPU, in a design without a host machine, runs on hyperthread pairs at
 * the first sibling speed that applies: its own {@code siblingSpeed} (1 means whole
 * cores); the design's {@code architecture.siblingSpeed}; 1 when the instance type the
 * cost prices for it is of a whole-core family, when the service runs serverless, or when
 * it runs as pods on Kubernetes; else 0.6. The one rule every reader of a part's vCPUs
 * goes by: a run's resolution writes into each part the speed the part does not set
 * ({@link #writeInto}), so the simulation and every analytic copy read it there, and
 * auto-sizing asks it before the part's instance type is known ({@link #beforeFamily}).
 */
public final class SiblingSpeeds {

	/** The parts whose vCPUs the rule counts. */
	private static final Set<String> COUNTED = Set.of(ServiceSpec.TYPE, DatabaseSpec.TYPE, CacheSpec.TYPE);

	/** A part's own sibling speed, as its config names it. */
	private static final String SIBLING_SPEED = "siblingSpeed";

	/** A part's vCPU, as its config names them. */
	private static final String CPU_CORES = "cpuCores";

	private final PriceSnapshots prices;

	/**
	 * Counts the vCPUs of designs priced from the given snapshots.
	 * @param prices the price snapshots, whose instance types say a part's family
	 */
	public SiblingSpeeds(PriceSnapshots prices) {
		this.prices = prices;
	}

	/**
	 * How a part's vCPUs count before its instance type is known: by its own setting, the
	 * design's, or its platform, else the default, which the instance family it lands on
	 * can still make whole cores.
	 * @param architecture the design's architecture, for its host and its sibling speed
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @return the speed and why; empty for a part the rule does not count (not a service,
	 * database or cache, or on a design with a host machine, whose speed applies)
	 */
	public static Optional<SiblingSpeed> beforeFamily(Architecture architecture, String nodeType,
			LenientSettings settings) {
		if (!COUNTED.contains(nodeType) || architecture.host() != null) {
			return Optional.empty();
		}
		OptionalDouble own = settings.number(SIBLING_SPEED);
		if (own.isPresent()) {
			return Optional.of(new SiblingSpeed(own.getAsDouble(), VcpuBasis.PART));
		}
		Double design = architecture.siblingSpeed();
		if (design != null) {
			return Optional.of(new SiblingSpeed(design, VcpuBasis.DESIGN));
		}
		return Optional.of(ofPlatform(nodeType, settings));
	}

	/**
	 * How the vCPUs of each part of a design with 2 or more vCPU count.
	 * @param design the design as a run simulates it: resolved, its sizes filled in
	 * @return each counted part's speed and why, by its id, in the design's order
	 */
	public Map<String, SiblingSpeed> speedsOf(ArchitectureDocument design) {
		PricingChoice pricing = PricingChoice.of(design.pricing(), this.prices);
		Map<String, SiblingSpeed> speeds = new LinkedHashMap<>();
		for (NodeSpec node : design.architecture().nodes()) {
			speedOf(design.architecture(), node.type(), LenientSettings.of(node.config()), pricing)
				.ifPresent((speed) -> speeds.put(node.id(), speed));
		}
		return speeds;
	}

	/**
	 * Writes into each part of a resolved design the sibling speed its vCPUs count at and
	 * it does not set: one the rule infers, and the design's below 1, so the run's input
	 * says what ran. The design's 1, which pins a design saved before schema 1.11 to
	 * whole cores, is what a part without a speed counts anyway, and its parts are left
	 * as they were, as is the digest of its run's result.
	 * @param resolved the resolved design as JSON, valid; changed in place
	 * @return the design it then describes, parsed again only when a speed was written
	 */
	public ArchitectureDocument writeInto(ObjectNode resolved) {
		ArchitectureDocument design = DesignDocuments.parse(resolved);
		Map<String, Double> written = new LinkedHashMap<>();
		speedsOf(design).forEach((id, speed) -> {
			if (isWritten(speed)) {
				written.put(id, speed.speed());
			}
		});
		if (written.isEmpty()) {
			return design;
		}
		for (JsonNode node : resolved.path("architecture").path("nodes")) {
			Double speed = written.get(node.path("id").asString(""));
			if (speed != null && node.path("config") instanceof ObjectNode config) {
				config.put(SIBLING_SPEED, speed);
			}
		}
		return DesignDocuments.parse(resolved);
	}

	/**
	 * How the vCPUs of each part of a run counted: as the design the run simulated gives
	 * them, with the basis the run's resolution read for a speed it wrote into a part
	 * that sets none as drawn.
	 * @param drawn the design as drawn, for the speeds its parts set
	 * @param resolved the design as the run simulated it
	 * @return each counted part's speed and why, by its id, in the design's order
	 */
	public Map<String, SiblingSpeed> asRun(ArchitectureDocument drawn, ArchitectureDocument resolved) {
		Set<String> setOnParts = new HashSet<>();
		drawn.architecture()
			.nodes()
			.stream()
			.filter((node) -> node.config().path(SIBLING_SPEED).isNumber())
			.forEach((node) -> setOnParts.add(node.id()));
		Map<String, SiblingSpeed> speeds = speedsOf(resolved);
		PricingChoice pricing = PricingChoice.of(resolved.pricing(), this.prices);
		for (NodeSpec node : resolved.architecture().nodes()) {
			SiblingSpeed speed = speeds.get(node.id());
			if (speed != null && speed.basis() == VcpuBasis.PART && !setOnParts.contains(node.id())) {
				speeds.put(node.id(),
						byTheRule(resolved.architecture(), node, pricing)
							.filter((written) -> Double.compare(written.speed(), speed.speed()) == 0)
							.orElse(speed));
			}
		}
		return speeds;
	}

	/**
	 * The parts of a run whose sibling speed its resolution wrote into them: those it
	 * counted by the design's speed, their family, their platform or the default.
	 * @param drawn the design as drawn
	 * @param resolved the design as the run simulated it
	 * @return the parts' ids
	 */
	public Set<String> writtenIn(ArchitectureDocument drawn, ArchitectureDocument resolved) {
		Set<String> written = new HashSet<>();
		asRun(drawn, resolved).forEach((id, speed) -> {
			if (speed.basis() != VcpuBasis.PART) {
				written.add(id);
			}
		});
		return written;
	}

	/**
	 * A resolved design without the sibling speeds its resolution wrote into some of its
	 * parts, for a resolution of its own to write them again.
	 * @param design the resolved design as JSON; changed in place
	 * @param parts the parts whose speed the resolution wrote ({@link #writtenIn})
	 */
	public static void leaveOut(ObjectNode design, Set<String> parts) {
		for (JsonNode node : design.path("architecture").path("nodes")) {
			if (parts.contains(node.path("id").asString("")) && node.path("config") instanceof ObjectNode config) {
				config.remove(SIBLING_SPEED);
			}
		}
	}

	/**
	 * How one part's vCPUs count.
	 * @param architecture the design's architecture
	 * @param nodeType the part's component type
	 * @param settings the part's settings, its size among them
	 * @param pricing the design's price snapshot and purchase, for its instance type
	 * @return the speed and why; empty for a part the rule does not count, or one of
	 * fewer than 2 vCPU
	 */
	static Optional<SiblingSpeed> speedOf(Architecture architecture, String nodeType, LenientSettings settings,
			PricingChoice pricing) {
		if (settings.number(CPU_CORES).orElse(0.0) < Resources.FEWEST_PAIRED_VCPU) {
			return Optional.empty();
		}
		return beforeFamily(architecture, nodeType, settings)
			.map((speed) -> ofFamily(speed, nodeType, settings, pricing));
	}

	// a speed the part does not set, and that is not the design's pin to whole cores
	private static boolean isWritten(SiblingSpeed speed) {
		boolean designsPairs = speed.basis() == VcpuBasis.DESIGN && speed.pairs();
		return speed.basis().inferred() || designsPairs;
	}

	/**
	 * How a part's vCPUs count by the rule alone, without the sibling speed its config
	 * holds.
	 * @param architecture the design's architecture
	 * @param node the part
	 * @param pricing the design's price snapshot and purchase
	 * @return the speed and why
	 */
	private static Optional<SiblingSpeed> byTheRule(Architecture architecture, NodeSpec node, PricingChoice pricing) {
		ObjectNode without = node.config().deepCopy();
		without.remove(SIBLING_SPEED);
		return speedOf(architecture, node.type(), LenientSettings.of(without), pricing);
	}

	private static SiblingSpeed ofPlatform(String nodeType, LenientSettings settings) {
		String platform = ServiceSpec.TYPE.equals(nodeType) ? settings.text("platform").orElse("") : "";
		if (Platform.SERVERLESS.json().equals(platform)) {
			return new SiblingSpeed(SiblingSpeed.WHOLE_CORES, VcpuBasis.SERVERLESS);
		}
		if (Platform.KUBERNETES.json().equals(platform)) {
			return new SiblingSpeed(SiblingSpeed.WHOLE_CORES, VcpuBasis.POD);
		}
		return new SiblingSpeed(Resources.DEFAULT_SIBLING_SPEED, VcpuBasis.DEFAULT);
	}

	/**
	 * The default turned to whole cores where the part's instance type is of a whole-core
	 * family.
	 * @param speed the speed by the part's settings, the design's and its platform
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @param pricing the design's price snapshot and purchase
	 * @return the speed
	 */
	private static SiblingSpeed ofFamily(SiblingSpeed speed, String nodeType, LenientSettings settings,
			PricingChoice pricing) {
		if (speed.basis() != VcpuBasis.DEFAULT) {
			return speed;
		}
		String provider = pricing.snapshot().provider();
		boolean wholeCores = pricedType(nodeType, settings, pricing)
			.filter((sku) -> WholeCoreFamilies.has(provider, sku.family()))
			.isPresent();
		return wholeCores ? new SiblingSpeed(SiblingSpeed.WHOLE_CORES, VcpuBasis.WHOLE_CORE_FAMILY) : speed;
	}

	/**
	 * The instance type the cost prices for a part: its own while it covers the part's
	 * CPU and memory, else the cheapest that does.
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @param pricing the design's price snapshot and purchase
	 * @return the type; empty without one (generic prices, a part not billed per machine,
	 * or none that covers it)
	 */
	private static Optional<Sku> pricedType(String nodeType, LenientSettings settings, PricingChoice pricing) {
		MachineSize size = new MachineSize(number(settings, CPU_CORES), number(settings, "memoryGib"), null);
		return pricing.instanceTypes().choose(nodeType, settings, size).map(Machine::sku);
	}

	private static @Nullable Double number(LenientSettings settings, String key) {
		OptionalDouble value = settings.number(key);
		return value.isPresent() ? value.getAsDouble() : null;
	}

}
