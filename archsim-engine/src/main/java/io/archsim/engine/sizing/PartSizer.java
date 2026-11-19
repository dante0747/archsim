package io.archsim.engine.sizing;

import java.util.Optional;
import java.util.Set;

import io.archsim.engine.load.NodeLoad;
import io.archsim.engine.pricing.MachineKind;
import io.archsim.engine.pricing.PricingChoice;
import io.archsim.engine.settings.LenientSettings;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.cache.CacheConfig;
import io.archsim.model.component.database.DatabaseConfig;
import io.archsim.model.component.loadbalancer.LoadBalancerConfig;
import io.archsim.model.component.queue.MessageQueueConfig;
import io.archsim.model.component.service.ServiceConfig;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;

/** Sizes the parts of one design, with its loads, clusters and price snapshot. */
final class PartSizer {

	private static final String MANAGED = "A managed service: the provider sizes it.";

	private static final String UNKNOWN_LOAD = "Its load cannot be estimated before a run.";

	private static final double TOLERANCE = 1e-9;

	private final ArchitectureDocument design;

	private final ComponentRegistry registry;

	private final PricingChoice pricing;

	private final SizingLoads loads;

	private final ClusterFit clusters;

	PartSizer(ArchitectureDocument design, ComponentRegistry registry, PricingChoice pricing, SizingLoads loads) {
		this.design = design;
		this.registry = registry;
		this.pricing = pricing;
		this.loads = loads;
		this.clusters = ClusterFit.of(design.architecture().nodes(), registry);
	}

	/**
	 * The auto size of one part.
	 * @param node the part
	 * @return its size; empty for a part with no resource specs, a config that is not
	 * valid or a type auto-sizing does not size
	 */
	Optional<PartSize> size(NodeSpec node) {
		Optional<ComponentSpec<?>> type = this.registry.find(node.type());
		if (type.isEmpty()) {
			return Optional.empty();
		}
		Set<Spec> specs = SpecKeys.of(type.get());
		if (specs.isEmpty()) {
			return Optional.empty();
		}
		Record config;
		try {
			config = type.get().parseConfig(node.config());
		}
		catch (BindingException ex) {
			// a config that is not valid is reported by validation, and not sized
			return Optional.empty();
		}
		return sized(node, config, specs);
	}

	private Optional<PartSize> sized(NodeSpec node, Record config, Set<Spec> specs) {
		LenientSettings settings = LenientSettings.of(RecordCodec.write(config));
		Optional<NodeLoad> load = this.loads.peak().node(node.id());
		double total = load.map(NodeLoad::total).orElse(0.0);
		if (settings.text("technology").filter(MachineKind.MANAGED_TECHNOLOGIES::contains).isPresent()) {
			return Optional.of(PartSize.skipped(total, MANAGED));
		}
		if (load.isEmpty()) {
			return Optional.of(PartSize.skipped(total, UNKNOWN_LOAD));
		}
		boolean onVm = MachineKind.of(node.type(), settings).map(MachineKind::isCompute).orElse(false);
		Placement peak = new Placement(onVm, this.loads.peakSpeeds().getOrDefault(node.id(), 1.0),
				Vcpus.of(this.design.architecture().host(), settings));
		return ofType(config, load.get(), peak).map((size) -> withBaseBusy(size, node, config, peak))
			.map((size) -> onMachine(size, node.type(), settings))
			.map((size) -> size.forSpecs(specs, total));
	}

	/**
	 * A size with the part's busy cores at the base load too, for CPU credits.
	 * @param size the size at the peak
	 * @param node the part
	 * @param config its settings
	 * @param peak where it runs, at the peak
	 * @return the size, with its busy cores at the base load when they are known
	 */
	private PartSize withBaseBusy(PartSize size, NodeSpec node, Record config, Placement peak) {
		Placement base = peak.atHostSpeed(this.loads.baseSpeeds().getOrDefault(node.id(), 1.0));
		return this.loads.base()
			.node(node.id())
			.flatMap((load) -> ofType(config, load, base))
			.map((atBase) -> atBase.need().get(Need.BUSY_CORES))
			.map((busy) -> size.withNeed(Need.BASE_BUSY_CORES, busy))
			.orElse(size);
	}

	/**
	 * A size on a machine: snapped to an instance type with a price snapshot, else
	 * rounded to a standard shape. Off an instance type, its smallest size is the 1-vCPU
	 * floor.
	 * @param size the size worked out
	 * @param nodeType the part's component type
	 * @param settings the part's settings
	 * @return the size on a machine
	 */
	private PartSize onMachine(PartSize size, String nodeType, LenientSettings settings) {
		PartSize placed = this.pricing.snapshot().hasShapes()
				? InstanceSnap.snapped(size, nodeType, settings, this.pricing)
				: StandardShapes.shaped(size, nodeType, settings);
		if (placed.sku() != null) {
			return placed;
		}
		Double need = placed.need().get(Need.CPU_CORES);
		return placed.withSmallest(need != null && need <= 1.0 + TOLERANCE);
	}

	/**
	 * The size of a part of a type auto-sizing sizes, for a load.
	 * @param config the part's settings
	 * @param load the requests it carries
	 * @param placement where it runs
	 * @return its size; empty for another type
	 */
	private Optional<PartSize> ofType(Record config, NodeLoad load, Placement placement) {
		if (config instanceof LoadBalancerConfig) {
			return Optional.of(BrokerSizer.balancer(load.total()));
		}
		return switch (config) {
			case ServiceConfig service ->
				Optional.of(this.clusters.fit(ServiceSizer.size(service, load.total(), placement), service));
			case DatabaseConfig database ->
				Optional.of(DatabaseSizer.size(database, load, this.design.workload(), placement));
			case CacheConfig cache ->
				Optional.of(CacheSizer.size(cache, load.total(), this.design.workload(), placement));
			case MessageQueueConfig queue ->
				Optional.of(BrokerSizer.queue(queue, load.total(), this.design.workload(), placement.onVm()));
			default -> Optional.empty();
		};
	}

}
