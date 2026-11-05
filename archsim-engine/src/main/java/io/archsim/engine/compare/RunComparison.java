package io.archsim.engine.compare;

import java.util.List;

/**
 * Two runs compared ({@code compare_reports}). Written by {@code RecordCodec}.
 *
 * @param labels what the comparison calls the runs
 * @param provenance what each run was, as its report stores it
 * @param inputDiff the settings the designs the runs simulated differ in, by path
 * @param kpis how each run figure changed
 * @param nodes each part of either run, the first run's parts first
 * @param bottleneck each run's first bottleneck finding, as its report stores it
 * @param explanations what changed, in words
 * @param warnings why the runs may differ other than by their designs
 * @param timeseries each run's figures per time bucket, as its report stores them
 */
public record RunComparison(Labels labels, ReportParts provenance, List<InputDifference> inputDiff,
		List<KpiChange> kpis, List<NodeComparison> nodes, ReportParts bottleneck, List<String> explanations,
		List<String> warnings, ReportParts timeseries) {

	/**
	 * Copies the lists, so the record does not change.
	 */
	public RunComparison {
		inputDiff = List.copyOf(inputDiff);
		kpis = List.copyOf(kpis);
		nodes = List.copyOf(nodes);
		explanations = List.copyOf(explanations);
		warnings = List.copyOf(warnings);
	}

}
