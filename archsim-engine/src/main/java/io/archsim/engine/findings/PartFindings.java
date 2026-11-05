package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.NodeSummary;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.text.PythonStrings;
import io.archsim.model.component.Category;
import io.archsim.model.component.cache.CacheSpec;
import io.archsim.model.component.loadbalancer.LoadBalancerSpec;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;

/**
 * Findings about parts: work wasted on clients that gave up, caches, and a healthy run.
 */
final class PartFindings {

	/** From this share of its completed work wasted, a part is named. */
	private static final double WASTEFUL = 0.01;

	/** From this share, it is critical. */
	private static final double MOSTLY_WASTED = 0.25;

	/** The decimals a cache's miss rate is cited with. */
	private static final int MISS_RATE_DECIMALS = 3;

	private static final String MISS = "miss";

	private PartFindings() {
	}

	/**
	 * Work finished for clients that had already given up, at the part that wasted the
	 * most of its completed work; not at balancers or clients.
	 * @param report the run's report
	 * @return the finding, when a part wasted at least 1% of its completed work
	 */
	static Optional<RunFinding> wastedWork(RunReport report) {
		List<Wasted> rows = wasteful(report);
		if (rows.isEmpty()) {
			return Optional.empty();
		}
		Wasted worst = rows.getFirst();
		String all = rows.stream()
			.map((row) -> row.node().label() + " " + FindingText.percent(row.share()))
			.collect(Collectors.joining("; "));
		String label = worst.node().label();
		return Optional
			.of(new RunFinding((worst.share() >= MOSTLY_WASTED) ? FindingSeverity.CRITICAL : FindingSeverity.WARNING,
					FindingKind.WASTED_WORK, worst.node().id(),
					FindingText.percent(worst.share()) + " of completed work at " + label + " was wasted",
					FindingText.count(worst.wasted()) + " requests finished at " + label
							+ " after their client had already timed out (share of completed work: " + all
							+ "). That capacity helped no one, which is how overload turns into near-zero goodput.",
					"Shed load early: set a queue limit or a queue timeout below the client timeout.",
					RunFinding.emptyEvidence()
						.put("wasted", worst.wasted())
						.put("completed", worst.node().summary().completed())));
	}

	// The parts that wasted at least 1% of their completed work, the most wasteful
	// first; not balancers or clients.
	private static List<Wasted> wasteful(RunReport report) {
		List<Wasted> rows = new ArrayList<>();
		for (ReportNode node : report.nodes()) {
			NodeSummary summary = node.summary();
			long wasted = summary.counter("wasted");
			long completed = Objects.requireNonNullElse(summary.completed(), 0L);
			boolean counted = !LoadBalancerSpec.TYPE.equals(node.type())
					&& !Category.TRAFFIC.json().equals(node.category());
			if (counted && wasted != 0 && completed != 0 && (double) wasted / completed >= WASTEFUL) {
				rows.add(new Wasted((double) wasted / completed, wasted, node));
			}
		}
		rows.sort(Comparator.comparingDouble((Wasted row) -> -row.share())
			.thenComparing((row) -> row.node().id(), PythonStrings::compare));
		return rows;
	}

	/**
	 * Each cache's hit ratio and where its misses went: down its miss link
	 * (read-through), or to the store its callers read on a miss (cache-aside).
	 * @param report the run's report
	 * @param resolved the design it simulated, for its links
	 * @return a finding for each cache with a hit ratio
	 */
	static List<RunFinding> caches(RunReport report, ArchitectureDocument resolved) {
		List<RunFinding> findings = new ArrayList<>();
		for (ReportNode node : report.nodes()) {
			Double hitRatio = node.summary().hitRatio();
			if (CacheSpec.TYPE.equals(node.type()) && hitRatio != null) {
				findings.add(cache(report, resolved, node, hitRatio));
			}
		}
		return findings;
	}

	private static RunFinding cache(RunReport report, ArchitectureDocument resolved, ReportNode node, double hitRatio) {
		double measuredS = report.window().measuredS();
		double missRps = (measuredS != 0) ? node.summary().counter("misses") / measuredS : 0.0;
		String misses = FindingText.wholeWithSeparators(missRps) + " reads/s";
		Optional<String> through = readThrough(report, resolved, node.id());
		Optional<String> aside = cacheAside(report, resolved, node.id());
		String then;
		if (through.isEmpty() && aside.isPresent()) {
			then = misses + " missed and " + aside.get();
		}
		else {
			then = misses + " continued to " + through.orElse("the data store");
		}
		String hits = FindingText.percent(hitRatio);
		return new RunFinding(FindingSeverity.INFO, FindingKind.CACHE, node.id(), node.label() + " hit ratio " + hits,
				hits + " of reads were served from the cache; " + then + ".", null,
				RunFinding.emptyEvidence()
					.put("hitRatio", hitRatio)
					.put("missRps", PythonRound.round(missRps, MISS_RATE_DECIMALS)));
	}

	// The part a read-through cache's miss link reaches.
	private static Optional<String> readThrough(RunReport report, ArchitectureDocument resolved, String cacheId) {
		return resolved.architecture()
			.edges()
			.stream()
			.filter((edge) -> edge.source().equals(cacheId) && MISS.equals(edge.sourcePort()))
			.map((edge) -> report.node(edge.target()))
			.flatMap(Optional::stream)
			.map(ReportNode::label)
			.findFirst();
	}

	// Who reads what on a miss, for a cache-aside cache, such as: API read them from DB.
	private static Optional<String> cacheAside(RunReport report, ArchitectureDocument resolved, String cacheId) {
		List<EdgeSpec> edges = resolved.architecture().edges();
		Set<String> callers = edges.stream()
			.filter((edge) -> edge.target().equals(cacheId) && !MISS.equals(edge.sourcePort()))
			.map(EdgeSpec::source)
			.collect(Collectors.toSet());
		return edges.stream()
			.filter((edge) -> callers.contains(edge.source()) && edge.config().readsOnMiss())
			.filter((edge) -> report.node(edge.target()).isPresent())
			.findFirst()
			.map((edge) -> label(report, edge.source()) + " read them from " + label(report, edge.target()));
	}

	private static String label(RunReport report, String id) {
		return report.node(id).orElseThrow(() -> new IllegalArgumentException("the report has no part " + id)).label();
	}

	/**
	 * The finding of a run without a root cause or symptom, naming its busiest part.
	 * @param report the run's report
	 * @return the finding
	 */
	static RunFinding healthy(RunReport report) {
		ReportNode busiest = null;
		for (ReportNode node : report.nodes()) {
			Double utilization = node.summary().utilization();
			if (utilization != null && (busiest == null || utilization > PartLoad.utilization(busiest.summary()))) {
				busiest = node;
			}
		}
		String detail = "Throughput tracks the offered load.";
		if (busiest != null) {
			detail = "The busiest component is " + busiest.label() + " at "
					+ FindingText.percent(PartLoad.utilization(busiest.summary())) + " utilization. " + detail;
		}
		return new RunFinding(FindingSeverity.OK, FindingKind.HEALTHY, (busiest != null) ? busiest.id() : null,
				"No component is saturated", detail, null, RunFinding.emptyEvidence());
	}

	/**
	 * A part that wasted some of its completed work.
	 *
	 * @param share the share of its completed work wasted
	 * @param wasted the requests it finished after their client gave up
	 * @param node the part
	 */
	private record Wasted(double share, long wasted, ReportNode node) {

	}

}
