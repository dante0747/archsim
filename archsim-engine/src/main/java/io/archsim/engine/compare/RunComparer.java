package io.archsim.engine.compare;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.findings.FindingKind;
import io.archsim.engine.report.ReportFinding;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.RunReport;

/**
 * Compares two finished runs ({@code compare_reports}): each run figure with a verdict,
 * each part side by side, the settings their simulated designs differ in, and what
 * changed in words. What it reads of a report, it reads through the report's records
 * (ADR-0039); each report's provenance, first bottleneck finding and time series, which
 * it shows, are copied whole as stored, and the simulated designs are diffed as stored
 * (ADR-0040).
 */
public final class RunComparer {

	private static final String BOTTLENECK = FindingKind.BOTTLENECK.json();

	private RunComparer() {
	}

	/**
	 * Compares two runs.
	 * @param first the first run's report, as the run stores it
	 * @param second the second run's report
	 * @param labels what to call the runs
	 * @return the comparison
	 * @throws tools.jackson.core.JacksonException when either JSON is not a report
	 */
	public static RunComparison compare(JsonNode first, JsonNode second, Labels labels) {
		RunReport firstRun = RunReport.read(first);
		RunReport secondRun = RunReport.read(second);
		Map<Kpi, KpiChange> kpis = new EnumMap<>(Kpi.class);
		for (Kpi kpi : Kpi.values()) {
			kpis.put(kpi, KpiChange.of(kpi, firstRun.summary(), secondRun.summary()));
		}
		List<NodeComparison> nodes = nodes(firstRun, secondRun);
		List<InputDifference> inputDiff = InputDiff.between(first.path("input"), second.path("input"));
		Bottleneck firstBottleneck = Bottleneck.of(firstRun, first);
		Bottleneck secondBottleneck = Bottleneck.of(secondRun, second);
		ComparisonText.Facts facts = new ComparisonText.Facts(labels, inputDiff, kpis, nodes, firstBottleneck.finding(),
				secondBottleneck.finding(), firstRun.provenance(), secondRun.provenance());
		ReportParts provenance = new ReportParts(first.get("provenance"), second.get("provenance"));
		ReportParts bottlenecks = new ReportParts(firstBottleneck.stored(), secondBottleneck.stored());
		ReportParts timeseries = new ReportParts(first.get("timeseries"), second.get("timeseries"));
		return new RunComparison(labels, provenance, inputDiff, List.copyOf(kpis.values()), nodes, bottlenecks,
				ComparisonText.explanations(facts), ComparisonText.warnings(facts), timeseries);
	}

	/**
	 * Each part of either run: the first run's in its order, then the second's others.
	 * @param first the first run
	 * @param second the second run
	 * @return the parts, side by side
	 */
	private static List<NodeComparison> nodes(RunReport first, RunReport second) {
		Map<String, ReportNode> inFirst = byId(first);
		Map<String, ReportNode> inSecond = byId(second);
		Set<String> ids = new LinkedHashSet<>(inFirst.keySet());
		ids.addAll(inSecond.keySet());
		return ids.stream().map((id) -> NodeComparison.of(id, inFirst.get(id), inSecond.get(id))).toList();
	}

	/**
	 * A run's parts by id, a later part of the same id in the place of the first, as a
	 * Python dict keeps them.
	 * @param report the run
	 * @return its parts
	 */
	private static Map<String, ReportNode> byId(RunReport report) {
		Map<String, ReportNode> byId = new LinkedHashMap<>();
		report.nodes().forEach((node) -> byId.put(node.id(), node));
		return byId;
	}

	/**
	 * A run's first bottleneck finding ({@code _primary_bottleneck}).
	 *
	 * @param finding the finding, as the records read it; {@code null} when the run found
	 * no bottleneck
	 * @param stored the same finding, as the report stores it; {@code null} when the run
	 * found no bottleneck
	 */
	private record Bottleneck(@Nullable ReportFinding finding, @Nullable JsonNode stored) {

		/**
		 * A run's first bottleneck finding, found once: the records keep the report's
		 * findings in its order.
		 * @param report the run
		 * @param json the run's report
		 * @return the finding, both ways
		 */
		static Bottleneck of(RunReport report, JsonNode json) {
			List<ReportFinding> findings = report.findings();
			return IntStream.range(0, findings.size())
				.filter((index) -> BOTTLENECK.equals(findings.get(index).kind()))
				.mapToObj((index) -> new Bottleneck(findings.get(index), json.path("findings").get(index)))
				.findFirst()
				.orElse(new Bottleneck(null, null));
		}

	}

}
