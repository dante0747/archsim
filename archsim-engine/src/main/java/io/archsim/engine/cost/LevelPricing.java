package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * One design priced at one load: each part's line at it, and what the pricing found. Its
 * lines, notes and flags change only while it prices.
 */
final class LevelPricing {

	private final ArchitectureDocument document;

	private final Catalog catalog;

	private final Map<String, Double> busy;

	private final double peakHours;

	private final List<String> notes = new ArrayList<>();

	private final List<CostLine> lines = new ArrayList<>();

	private final CostFlags flags = new CostFlags();

	private final DesignPricing pricing;

	private final PriceSnapshot snapshot;

	private final Quotes quotes;

	private final ManagedLines managed;

	private final List<String> clusterIds;

	private final LevelLoad load;

	private final Map<String, InstanceCount> settled;

	private LevelPricing(Catalog catalog, ArchitectureDocument document, Inputs inputs, LevelLoad load) {
		this.document = document;
		this.catalog = catalog;
		this.busy = inputs.busy().at(load.level());
		this.peakHours = inputs.peakHours();
		this.pricing = DesignPricing.of(document.pricing(), catalog.prices());
		this.pricing.note().ifPresent(this.notes::add);
		this.snapshot = this.pricing.snapshot();
		this.quotes = new Quotes(this.snapshot, this.pricing.purchase(), inputs.provenance());
		this.managed = new ManagedLines(this.snapshot, new DynamoDbLine(this.snapshot, catalog.registry()));
		this.clusterIds = KubernetesLines.clusterIds(document);
		this.load = load;
		this.settled = SettledCounts.of(document, catalog.registry(), load);
	}

	/**
	 * A design priced at a load.
	 * @param catalog the component types and the price snapshots
	 * @param document the design as simulated
	 * @param inputs the provenance of its values and the CPU its parts keep busy
	 * @param load what each part handles at the load
	 * @return each part's line at the load, the dearest first, the month at it and its
	 * notes
	 */
	static PricedLoad priced(Catalog catalog, ArchitectureDocument document, Inputs inputs, LevelLoad load) {
		LevelPricing pricing = new LevelPricing(catalog, document, inputs, load);
		pricing.addLines();
		return pricing.result();
	}

	// every part's line, the dearest first, with the implied pods' control plane
	private void addLines() {
		for (NodeSpec node : this.document.architecture().nodes()) {
			if (ComponentFamily.of(node.type(), this.catalog.registry()) != ComponentFamily.TRAFFIC) {
				addLine(new CostPart(node, this.catalog.registry(), this.catalog.facts()));
			}
		}
		Double plane = this.pricing.rate("kubernetesControlPlaneHour");
		if (this.flags.impliedPods() && plane != null && plane != 0) {
			this.lines.add(KubernetesLines.controlPlane(plane, this.snapshot.snapshotDate()));
		}
		this.lines.sort(CostLine::dearestFirst);
	}

	private PricedLoad result() {
		double monthly = FloatSum.of(this.lines.stream().mapToDouble(CostLine::monthly));
		Double perMillion = this.load.perMillion(monthly);
		CostNotes.add(this.notes, this.flags,
				new CostNotes.Context(this.snapshot, this.pricing.purchase().option(),
						new CostNotes.BilledMonth(this.document.workload(), this.peakHours),
						perRequest() || perMillion != null, this.load));
		return new PricedLoad(this.lines, monthly, this.load.successfulRps(), this.notes, this.flags.priced(),
				this.pricing.rates(), this.pricing.toJson());
	}

	/**
	 * Adds the part's line: on a cluster, per call, managed, on implied pods, or per
	 * instance-hour.
	 * @param part the part
	 */
	private void addLine(CostPart part) {
		String cluster = KubernetesLines.clusterOf(part, this.clusterIds);
		if (cluster != null) {
			this.lines
				.add(KubernetesLines.onCluster(part, this.document.node(cluster).map(NodeSpec::name).orElse(cluster)));
			return;
		}
		if (ExternalApiSpec.TYPE.equals(part.type())) {
			addCalls(part);
			return;
		}
		if (this.quotes.origin(part) != PriceBasis.SET) {
			Optional<CostLine> managedLine = this.managed.line(part, this.load.usage().get(part.id()),
					this.document.workload().keyCount());
			if (managedLine.isPresent()) {
				addSnapshotPriced(managedLine.get());
				return;
			}
			Optional<ImpliedPods> pods = ImpliedPods.of(part, this.snapshot, this.pricing.purchase().option());
			if (pods.isPresent()) {
				addImpliedPods(part, pods.get());
				return;
			}
		}
		addQuote(part);
	}

	private void addCalls(CostPart part) {
		CostLine line = ExternalApiLine.of(part, this.load.usage().get(part.id()));
		this.flags.markCalls(line);
		this.lines.add(line);
	}

	/**
	 * Adds a service's pods on implied nodes; their control plane is added once, after
	 * every part.
	 * @param part the service
	 * @param pods the node type its pods run on
	 */
	private void addImpliedPods(CostPart part, ImpliedPods pods) {
		this.flags.markImpliedPods();
		addSnapshotPriced(KubernetesLines.onPods(part, pods, instances(part), this.snapshot.snapshotDate()));
	}

	/**
	 * Adds a line the snapshot prices other than per instance-hour.
	 * @param line the line
	 */
	private void addSnapshotPriced(CostLine line) {
		this.flags.markPriced();
		this.flags.markSnapshotPriced();
		this.lines.add(line);
	}

	private void addQuote(CostPart part) {
		Quote quote = this.quotes.quote(part);
		this.flags.markQuote(quote, this.snapshot);
		this.lines.add(QuoteLines.of(part, quote, instances(part),
				new QuoteLines.Context(this.snapshot, this.busy.get(part.id()))));
	}

	/**
	 * The part's billable instances at the load: the count an autoscaled service or a
	 * cluster settles at, else what the load gives.
	 * @param part the part
	 * @return its instances and how they were counted
	 */
	private InstanceCount instances(CostPart part) {
		InstanceCount count = this.settled.get(part.id());
		return (count != null) ? count : this.load.instances(part, this.catalog.registry());
	}

	private boolean perRequest() {
		return this.lines.stream().anyMatch((line) -> line.unit() == CostUnit.MILLION && line.monthly() > 0);
	}

	/**
	 * What prices a design: the component types, what they are, and the price snapshots.
	 *
	 * @param registry the component types
	 * @param facts the catalog's facts about them
	 * @param prices the price snapshots on this machine
	 */
	record Catalog(ComponentRegistry registry, ComponentFacts facts, PriceSnapshots prices) {
	}

	/**
	 * What a design is priced with beyond the design and its load.
	 *
	 * @param provenance the document's provenance kind by path: a price marked measured
	 * or assumed is the user's, whatever its value
	 * @param busy the CPU cores one instance of each part keeps busy at each load
	 * @param peakHours the hours of the month billed at the peak
	 */
	record Inputs(Map<String, ProvenanceKind> provenance, BusyCores busy, double peakHours) {
	}

}
