package io.archsim.engine.check;

import java.util.List;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.findings.FindingKind;
import io.archsim.engine.report.ReportCost;
import io.archsim.engine.report.ReportFinding;
import io.archsim.engine.report.ReportInput;
import io.archsim.engine.report.RunReport;
import io.archsim.engine.report.RunSummary;
import io.archsim.engine.slo.RunFigures;
import io.archsim.engine.slo.SloResult;
import io.archsim.model.codec.OmitWhenDefault;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Objectives;

/**
 * The figures a check compares, from one run of a design ({@code measure}). Written by
 * {@code RecordCodec}.
 *
 * @param name the design's name, as the run simulated it
 * @param scenarioId the scenario the run simulated; {@code null} for the design itself
 * @param p95 the run's p95 latency, in milliseconds; {@code null} when it has none
 * @param p99 its p99 latency; {@code null} when it has none
 * @param successRate the share of its requests that succeeded; {@code null} when it has
 * none
 * @param throughputRps the requests that succeeded per second; {@code null} when it has
 * none
 * @param offeredRps the requests offered per second; {@code null} when it has none
 * @param costMonthly the monthly cost; {@code null} when no part has a price
 * @param objectives the objectives the design sets, with their defaults
 * @param slo the run judged against them
 * @param verdict that judgement, in words
 * @param bottleneck the title of the run's first bottleneck finding; {@code null} for
 * none
 * @param resultDigest the digest of the run's result
 * @param engineVersion the version of the engine that ran it
 * @param confidence how far the run can be trusted, as its report says
 * @param knee the highest load within the objectives; {@code null} when not swept, or
 * missed already at the lightest load
 * @param kneeVerdict the sweep's verdict; left out when not swept
 * @param kneeRange the range of loads swept; left out when not swept
 * @param kneeResolution the step of the load grid the breaking point was read from; left
 * out when not swept
 */
public record Measurement(String name, @Nullable String scenarioId, @Nullable Double p95, @Nullable Double p99,
		@Nullable Double successRate, @Nullable Double throughputRps, @Nullable Double offeredRps,
		@Nullable Double costMonthly, Objectives objectives, SloResult slo, String verdict, @Nullable String bottleneck,
		String resultDigest, @Nullable String engineVersion, MeasuredConfidence confidence, @Nullable Double knee,
		@OmitWhenDefault @Nullable String kneeVerdict, @OmitWhenDefault @Nullable List<Double> kneeRange,
		@OmitWhenDefault @Nullable Double kneeResolution) {

	private static final String BOTTLENECK = FindingKind.BOTTLENECK.json();

	/**
	 * Copies the range swept, so the record does not change.
	 */
	public Measurement {
		kneeRange = (kneeRange != null) ? List.copyOf(kneeRange) : null;
	}

	/**
	 * What a check measures of a run ({@code measure}, without a sweep of the breaking
	 * point): its figures, its cost when any part has a price, the design's objectives
	 * (with their defaults) and the run judged against them, its first bottleneck and its
	 * confidence.
	 * @param document the design, whose objectives the run is judged by
	 * @param scenarioId the scenario the run simulated; {@code null} for the design
	 * itself
	 * @param report the run's report
	 * @return the measurement
	 * @throws NullPointerException when the report does not have the design it ran
	 */
	public static Measurement of(ArchitectureDocument document, @Nullable String scenarioId, RunReport report) {
		RunSummary summary = report.summary();
		SloResult slo = SloResult.evaluate(document.objectives(), RunFigures.of(summary));
		ReportInput input = Objects.requireNonNull(report.input(), "a run's report has the design it ran");
		ReportCost cost = report.cost();
		Double costMonthly = (cost != null && Boolean.TRUE.equals(cost.hasCost())) ? cost.monthly() : null;
		String bottleneck = report.firstFinding(BOTTLENECK).map(ReportFinding::title).orElse(null);
		return new Measurement(input.metadata().name(), scenarioId, summary.latencyMs().p95(),
				summary.latencyMs().p99(), summary.successRate(), summary.throughputRps(), summary.offeredRps(),
				costMonthly, document.objectives(), slo, slo.text(), bottleneck, report.provenance().resultDigest(),
				report.provenance().engineVersion(), MeasuredConfidence.of(report.confidence()), null, null, null,
				null);
	}

	/**
	 * This measurement with the design's breaking point, read on a shared load grid.
	 * @param found the breaking point
	 * @return the measurement
	 */
	public Measurement withKnee(KneeFinding found) {
		return new Measurement(this.name, this.scenarioId, this.p95, this.p99, this.successRate, this.throughputRps,
				this.offeredRps, this.costMonthly, this.objectives, this.slo, this.verdict, this.bottleneck,
				this.resultDigest, this.engineVersion, this.confidence, found.knee(), found.kneeVerdict(),
				found.kneeRange(), found.kneeResolution());
	}

}
