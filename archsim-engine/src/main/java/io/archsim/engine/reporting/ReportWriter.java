package io.archsim.engine.reporting;

import java.util.List;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.provenance.Input;
import io.archsim.engine.provenance.InputProvenances;
import io.archsim.engine.run.PreparedRun;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.Workload;
import io.archsim.model.pricing.PriceSnapshots;

/**
 * Writes a finished run's report, as the Python engine's {@code build_report} wrote it:
 * the window, what clients experienced, time series, each part (with its formulas and
 * status) and each link, the findings, the sampled requests' traces, where a request's
 * time goes, the assumptions, the sizing, the design simulated, its cost, review and
 * instant estimate, the run's confidence, and the provenance with its digests. A host
 * machine's summary comes with the shared host's slice; until then the simulator refuses
 * a design with one.
 */
public final class ReportWriter {

	private static final int TIME_DIGITS = 3;

	private final PartsWriter parts;

	private final InputProvenances provenances;

	private final Assumptions assumptions;

	private final RunAnalyses analyses;

	/**
	 * A writer of reports.
	 * @param registry the component types
	 * @param prices the price snapshots a design's prices are pinned to
	 */
	public ReportWriter(ComponentRegistry registry, PriceSnapshots prices) {
		this.parts = new PartsWriter(registry);
		this.provenances = new InputProvenances(registry);
		this.assumptions = new Assumptions(registry);
		this.analyses = new RunAnalyses(registry, prices);
	}

	/**
	 * The report of a run.
	 * @param run the finished run
	 * @param wallSeconds the wall-clock time the run took, from the design to its last
	 * event, in seconds
	 * @return the report
	 */
	public ObjectNode write(SimulatedRun run, double wallSeconds) {
		PreparedRun prepared = run.prepared();
		ArchitectureDocument resolved = prepared.resolved();
		Labels labels = new Labels(resolved);
		ObjectNode report = Figures.NODES.objectNode();
		report.put("reportVersion", EngineVersions.REPORT);
		report.set("window", window(run));
		ObjectNode summary = new ClientSummary(run, labels).write();
		report.set("summary", summary);
		report.set("timeseries", timeseries(run));
		ArrayNode nodes = this.parts.nodes(run);
		report.set("nodes", nodes);
		report.set("edges", this.parts.edges(run));
		this.parts.writeStatuses(report);
		ObjectNode input = DesignDocuments.simulationInput(resolved);
		DraftReport draft = new DraftReport(report, Provenance.write(run, report, input, wallSeconds));
		draft.set("findings", this.analyses.findings(draft.read(), resolved));
		draft.set("traces", new Traces(run.context().traces(), labels).write());
		draft.set("explain", SystemExplain.write(run, summary, labels));
		List<Input> inputs = this.provenances.of(resolved);
		draft.set("assumptions", this.assumptions.write(prepared, inputs));
		ArchitectureDocument simulated = DesignDocuments.parse(input);
		this.analyses.writeDesign(draft, prepared, input, simulated);
		this.analyses.writeConfidence(draft, simulated.architecture(), inputs);
		return draft.finish();
	}

	private static ObjectNode window(SimulatedRun run) {
		Workload workload = run.prepared().resolved().workload();
		ObjectNode window = Figures.NODES.objectNode();
		window.put("warmupS", workload.warmupS());
		window.put("durationS", workload.durationS());
		window.put("measuredS", run.windowSeconds());
		window.put("drainS", run.drainS());
		window.put("simulatedS", PythonRound.round(run.finalTime() / Figures.MS_PER_SECOND, TIME_DIGITS));
		return window;
	}

	private static ObjectNode timeseries(SimulatedRun run) {
		Timeline timeline = run.timeline();
		ClientMetrics clients = run.clients();
		int used = run.usedBuckets();
		double bucketS = timeline.bucketMs() / Figures.MS_PER_SECOND;
		ObjectNode series = Figures.NODES.objectNode();
		series.put("bucketS", bucketS);
		ArrayNode times = series.putArray("t");
		for (int bucket = 0; bucket < used; bucket++) {
			times.add(PythonRound.round(bucket * bucketS, TIME_DIGITS));
		}
		series.set("offeredRps", Figures.roundedSeries(clients.offered().rateSeries(), used));
		series.set("throughputRps", Figures.roundedSeries(clients.succeeded().rateSeries(), used));
		series.set("errorRps", Figures.roundedSeries(clients.errors().rateSeries(), used));
		series.set("timeoutRps", Figures.roundedSeries(clients.timeouts().rateSeries(), used));
		series.set("attemptRps", Figures.roundedSeries(clients.attempts().rateSeries(), used));
		series.set("latencyP50Ms", Figures.series(clients.latencySeries().percentileSeries(Percentiles.MEDIAN), used));
		series.set("latencyP95Ms", Figures.series(clients.latencySeries().percentileSeries(Percentiles.P95), used));
		series.set("latencyP99Ms", Figures.series(clients.latencySeries().percentileSeries(Percentiles.P99), used));
		return series;
	}

}
