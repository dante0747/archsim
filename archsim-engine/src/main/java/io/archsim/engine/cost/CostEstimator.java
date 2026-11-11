package io.archsim.engine.cost;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.archsim.engine.estimate.InstantEstimate;
import io.archsim.engine.load.BaseRate;
import io.archsim.engine.load.ComponentFamily;
import io.archsim.engine.load.LoadEstimator;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.report.RunReport;
import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.external.ExternalApiSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.ProvenanceKind;
import io.archsim.model.pricing.PriceSnapshot;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * A design's monthly cost, from each part's price per instance-hour ({@link Quotes}),
 * managed and per-request parts at a price snapshot's rates ({@link ManagedLines}),
 * third-party APIs per call ({@link ExternalApiLine}), and services on Kubernetes
 * ({@link KubernetesLines}). Per-request costs are at the workload's steady base load:
 * before a run, from the static load estimate at that rate; after one, from the rates the
 * run measured at it ({@link MeasuredUsage}), with autoscaled parts at their average
 * active instances and the cost per million successful requests. These are planning
 * estimates, not quotes.
 */
public final class CostEstimator {

	/** The hours a month is billed for. */
	public static final int HOURS_PER_MONTH = 730;

	/**
	 * The share of an implied Kubernetes node's CPU and memory left for pods (the
	 * cluster's default).
	 */
	public static final double KUBERNETES_ALLOCATABLE = 0.9;

	/** SQS bills each API call: a message is sent, received and deleted. */
	public static final int SQS_REQUESTS_PER_MESSAGE = 3;

	/** Illustrative: what a load balancer processes per request, in KB. */
	public static final double ALB_KB_PER_REQUEST = 10.0;

	/** A load balancer's capacity unit covers this many GB processed an hour. */
	public static final double ALB_GB_PER_LCU_HOUR = 1.0;

	/** What a snapshot's prices leave out. */
	public static final String NOT_MODELLED = "Not modelled: Multi-AZ deployments, reserved instances and savings "
			+ "plans, data transfer and NAT, and free tiers.";

	/** The seconds a month's per-request costs are counted over. */
	public static final double SECONDS_PER_MONTH = HOURS_PER_MONTH * 3600.0;

	private final ComponentRegistry registry;

	private final PriceSnapshots prices;

	private final ComponentFacts facts;

	private final LoadEstimator loads;

	/**
	 * Prices designs made of a registry's types.
	 * @param registry the component types
	 * @param prices the price snapshots on this machine
	 */
	public CostEstimator(ComponentRegistry registry, PriceSnapshots prices) {
		this.registry = registry;
		this.prices = prices;
		this.facts = new ComponentFacts(registry);
		this.loads = new LoadEstimator(registry);
	}

	/**
	 * The monthly cost of a design before a run.
	 * @param document the design as simulated: resolved and auto-sized
	 * @param provenance the document's provenance kind by path: a price marked measured
	 * or assumed is the user's, whatever its value
	 * @param baseBusy the CPU cores one instance of each part keeps busy at the base
	 * load, so a burstable type above its baseline is charged its surplus CPU credits
	 * @param estimate the design's instant estimate, for a function's duration
	 * @return the cost
	 */
	public CostEstimate estimate(ArchitectureDocument document, Map<String, ProvenanceKind> provenance,
			Map<String, Double> baseBusy, InstantEstimate estimate) {
		return new Pricing(document, new Inputs(provenance, baseBusy), BaseUsage.of(document, this.loads, estimate))
			.estimate();
	}

	/**
	 * The monthly cost of a design after a run of it.
	 * @param document the design the run simulated: resolved and auto-sized
	 * @param provenance the document's provenance kind by path: a price marked measured
	 * or assumed is the user's, whatever its value
	 * @param baseBusy the CPU cores one instance of each part keeps busy at the base
	 * load, so a burstable type above its baseline is charged its surplus CPU credits
	 * @param report the run's report
	 * @return the cost
	 * @throws IllegalArgumentException when the report lists no parts
	 */
	public CostEstimate estimateAfterRun(ArchitectureDocument document, Map<String, ProvenanceKind> provenance,
			Map<String, Double> baseBusy, RunReport report) {
		return new Pricing(document, new Inputs(provenance, baseBusy), MeasuredUsage.of(document, this.loads, report))
			.estimate();
	}

	/**
	 * Dearest first. Equal costs keep their order, as in Python's sort: {@code -0.0} and
	 * {@code 0.0} are equal there, where {@link Double#compare} orders them.
	 * @param first a line
	 * @param second another line
	 * @return the order of the two
	 */
	private static int dearestFirst(CostLine first, CostLine second) {
		if (first.monthly() > second.monthly()) {
			return -1;
		}
		return (first.monthly() < second.monthly()) ? 1 : 0;
	}

	/**
	 * What a design is priced with beyond the design and its base load.
	 *
	 * @param provenance the document's provenance kind by path
	 * @param baseBusy the CPU cores one instance of each part keeps busy at the base load
	 */
	private record Inputs(Map<String, ProvenanceKind> provenance, Map<String, Double> baseBusy) {
	}

	/** One design being priced: its lines, notes and flags change only in here. */
	private final class Pricing {

		private final ArchitectureDocument document;

		private final Map<String, Double> baseBusy;

		private final List<String> notes = new ArrayList<>();

		private final List<CostLine> lines = new ArrayList<>();

		private final CostFlags flags = new CostFlags();

		private final DesignPricing pricing;

		private final PriceSnapshot snapshot;

		private final Quotes quotes;

		private final ManagedLines managed;

		private final List<String> clusterIds;

		private final BaseLoad base;

		Pricing(ArchitectureDocument document, Inputs inputs, BaseLoad base) {
			this.document = document;
			this.baseBusy = inputs.baseBusy();
			this.pricing = DesignPricing.of(document.pricing(), CostEstimator.this.prices);
			this.pricing.note().ifPresent(this.notes::add);
			this.snapshot = this.pricing.snapshot();
			this.quotes = new Quotes(this.snapshot, this.pricing.purchase(), inputs.provenance());
			this.managed = new ManagedLines(this.snapshot,
					new DynamoDbLine(this.snapshot, CostEstimator.this.registry));
			this.clusterIds = KubernetesLines.clusterIds(document);
			this.base = base;
		}

		CostEstimate estimate() {
			for (NodeSpec node : this.document.architecture().nodes()) {
				if (ComponentFamily.of(node.type(), CostEstimator.this.registry) != ComponentFamily.TRAFFIC) {
					addLine(new CostPart(node, CostEstimator.this.registry, CostEstimator.this.facts));
				}
			}
			Double plane = this.pricing.rate("kubernetesControlPlaneHour");
			if (this.flags.impliedPods() && plane != null && plane != 0) {
				this.lines.add(KubernetesLines.controlPlane(plane, this.snapshot.snapshotDate()));
			}
			this.lines.sort(CostEstimator::dearestFirst);
			double monthly = FloatSum.of(this.lines.stream().mapToDouble(CostLine::monthly));
			Double perMillion = this.base.perMillion(monthly);
			CostNotes.add(this.notes, this.flags, new CostNotes.Context(this.snapshot, this.pricing.purchase().option(),
					BaseRate.of(this.document.workload()), perRequest() || perMillion != null, this.base));
			return new CostEstimate(monthly, perMillion, this.lines, this.notes, this.flags.priced(), "USD",
					HOURS_PER_MONTH, this.pricing.rates(), this.pricing.toJson());
		}

		/**
		 * Adds the part's line: on a cluster, per call, managed, on implied pods, or per
		 * instance-hour.
		 * @param part the part
		 */
		private void addLine(CostPart part) {
			String cluster = KubernetesLines.clusterOf(part, this.clusterIds);
			if (cluster != null) {
				this.lines.add(KubernetesLines.onCluster(part,
						this.document.node(cluster).map(NodeSpec::name).orElse(cluster)));
				return;
			}
			if (ExternalApiSpec.TYPE.equals(part.type())) {
				addCalls(part);
				return;
			}
			if (this.quotes.origin(part) != PriceBasis.SET) {
				Optional<CostLine> managedLine = this.managed.line(part, this.base.usage().get(part.id()),
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
			CostLine line = ExternalApiLine.of(part, this.base.usage().get(part.id()));
			this.flags.markCalls(line);
			this.lines.add(line);
		}

		/**
		 * Adds a service's pods on implied nodes; their control plane is added once,
		 * after every part.
		 * @param part the service
		 * @param pods the node type its pods run on
		 */
		private void addImpliedPods(CostPart part, ImpliedPods pods) {
			this.flags.markImpliedPods();
			addSnapshotPriced(KubernetesLines.onPods(part, pods, this.base.instances(part, CostEstimator.this.registry),
					this.snapshot.snapshotDate()));
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
			this.lines.add(QuoteLines.of(part, quote, this.base.instances(part, CostEstimator.this.registry),
					new QuoteLines.Context(this.snapshot, this.baseBusy.get(part.id()))));
		}

		private boolean perRequest() {
			return this.lines.stream().anyMatch((line) -> line.unit() == CostUnit.MILLION && line.monthly() > 0);
		}

	}

}
