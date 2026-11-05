package io.archsim.engine.report;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * A run's report, as far as the engine reads it (ADR-0039): the parts each ported
 * function reads, under the report's names. A port that reads another value adds it here.
 *
 * @param summary the run's figures as a whole
 * @param nodes each part's figures, in the design's order
 * @param findings what the run found, most severe first
 * @param provenance what the run was, and the digest of its result
 * @param timeseries the run's figures per time bucket
 * @param window the run's measured window
 */
public record RunReport(RunSummary summary, List<ReportNode> nodes, List<ReportFinding> findings,
		ReportProvenance provenance, ReportTimeseries timeseries, ReportWindow window) {

	/**
	 * Checks that the report has the parts every report has, and copies its lists.
	 */
	public RunReport {
		Objects.requireNonNull(summary, "a report has its summary");
		nodes = List.copyOf(Objects.requireNonNull(nodes, "a report has its parts"));
		findings = List.copyOf(Objects.requireNonNull(findings, "a report has its findings"));
		Objects.requireNonNull(provenance, "a report has its provenance");
		Objects.requireNonNull(timeseries, "a report has its time series");
		Objects.requireNonNull(window, "a report has its window");
	}

	/**
	 * Reads a report.
	 * @param json the report, as a run stores it
	 * @return what the engine reads of it
	 * @throws tools.jackson.core.JacksonException when the JSON is not a report
	 */
	public static RunReport read(JsonNode json) {
		return ReportJson.read(json, RunReport.class);
	}

	/**
	 * A part of the run.
	 * @param id the part's id
	 * @return the part, if the run has it
	 */
	public Optional<ReportNode> node(String id) {
		return this.nodes.stream().filter((node) -> node.id().equals(id)).findFirst();
	}

	/**
	 * The first finding of a kind.
	 * @param kind such as {@code bottleneck}
	 * @return the finding, if the run found one
	 */
	public Optional<ReportFinding> firstFinding(String kind) {
		return this.findings.stream().filter((finding) -> kind.equals(finding.kind())).findFirst();
	}

}
