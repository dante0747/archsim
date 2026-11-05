package io.archsim.engine.findings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import io.archsim.engine.numeric.TrueDivision;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;

/**
 * A rule-based analysis of a run: each part's status, the bottlenecks (a saturated part
 * held up by a saturated part downstream is a symptom, not a cause), what clients saw,
 * work wasted on clients that gave up, caches, and the findings of data stores, third
 * parties, faults, autoscaling, serverless functions and queues. Every finding cites the
 * figures it rests on.
 */
public final class RunAnalysis {

	private final ComponentRegistry registry;

	/**
	 * An analysis over a registry's component types.
	 * @param registry the component types, to read each part's settings
	 */
	public RunAnalysis(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Analyzes a run.
	 * @param report the run's report
	 * @param resolved the design as the run simulated it: resolved and auto-sized
	 * @return its findings, the bottlenecks first, or only that no request was generated
	 * @throws ArithmeticException when the report's buckets have no length
	 */
	public List<RunFinding> analyze(RunReport report, ArchitectureDocument resolved) {
		Map<String, Classification> statuses = classify(report);
		if (report.summary().arrived() == 0) {
			return List
				.of(new RunFinding(FindingSeverity.INFO, FindingKind.NO_TRAFFIC, null, "No requests were generated",
						"The workload offered 0 requests in the measurement window, so there is nothing to analyse.",
						null, RunFinding.emptyEvidence()));
		}
		RunContext run = RunContext.of(report, resolved, statuses, this.registry);
		Bottlenecks bottlenecks = Bottlenecks.of(run);
		List<RunFinding> findings = new ArrayList<>(bottlenecks.findings());
		findings.addAll(ClientFindings.of(run));
		PartFindings.wastedWork(report).ifPresent(findings::add);
		findings.addAll(PartFindings.caches(report, resolved));
		if (bottlenecks.none()) {
			findings.addFirst(PartFindings.healthy(report));
		}
		findings.addAll(DataFindings.of(run));
		findings.addAll(DynamicsFindings.of(run));
		return List.copyOf(findings);
	}

	/**
	 * Each part's status in a run.
	 * @param report the run's report
	 * @return each part's status and why, by its id, in the report's order
	 * @throws ArithmeticException when the report's buckets have no length
	 */
	public static Map<String, Classification> classify(RunReport report) {
		double bucket = report.timeseries().bucketS();
		int from = (int) Math.ceil(TrueDivision.of(report.window().warmupS(), bucket));
		int to = (int) Math.floor(TrueDivision.of(report.window().durationS(), bucket));
		Map<String, Classification> statuses = new LinkedHashMap<>();
		for (ReportNode node : report.nodes()) {
			statuses.put(node.id(), Classification.of(node, from, to));
		}
		return Collections.unmodifiableMap(statuses);
	}

}
