package io.archsim.engine.reporting;

import java.util.Map;
import java.util.Objects;
import java.util.Set;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.components.client.ClientMetrics;
import io.archsim.engine.metrics.Timeline;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.engine.version.EngineVersions;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;
import io.archsim.model.document.Workload;

/**
 * Writes a finished run's report, as the Python engine's {@code build_report} wrote it,
 * as far as the port has come: the window, what clients experienced, time series, each
 * part and each link, the sampled requests' traces, where a request's time goes, and the
 * provenance's counts. The analyses a full report adds (findings, the parts' formulas and
 * status, assumptions, sizing, cost, review, estimate, confidence, digests) come with a
 * later slice of the port.
 */
public final class ReportWriter {

	private static final int TIME_DIGITS = 3;

	private final ComponentRegistry registry;

	/**
	 * A writer of reports.
	 * @param registry the component types, for each part's category
	 */
	public ReportWriter(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * The report of a run, as far as the port has come.
	 * @param run the finished run
	 * @return the report
	 */
	public ObjectNode write(SimulatedRun run) {
		ArchitectureDocument resolved = run.prepared().resolved();
		Labels labels = new Labels(resolved);
		ObjectNode report = Figures.NODES.objectNode();
		report.put("reportVersion", EngineVersions.REPORT);
		report.set("window", window(run));
		ObjectNode summary = new ClientSummary(run, labels).write();
		report.set("summary", summary);
		report.set("timeseries", timeseries(run));
		report.set("nodes", nodes(run, resolved));
		report.set("edges", edges(run, resolved));
		report.set("traces", new Traces(run.context().traces(), labels).write());
		report.set("explain", SystemExplain.write(run, summary, labels));
		ObjectNode provenance = report.putObject("provenance");
		provenance.put("seed", resolved.workload().seed());
		provenance.put("requestsGenerated", run.clients().offered().total());
		provenance.put("eventsProcessed", run.events());
		return report;
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

	private ArrayNode nodes(SimulatedRun run, ArchitectureDocument resolved) {
		ArrayNode nodes = Figures.NODES.arrayNode();
		Map<String, Record> configs = run.prepared().validation().configs();
		for (NodeSpec node : resolved.architecture().nodes()) {
			RuntimeComponent component = run.context().component(node.id());
			NodeEntry entry = new NodeEntry(component);
			NodeSeries series = new NodeSeries(component, run.usedBuckets());
			ObjectNode summary = entry.summary();
			ObjectNode timeseries = series.write();
			summary.set("latencyMs", Figures.latency(component.metrics().residence().window().summary()));
			summary.set("peakUtilization", series.peakUtilization(timeseries));
			ObjectNode written = nodes.addObject();
			written.put("id", node.id());
			written.put("type", node.type());
			written.put("label", Labels.name(node));
			written.put("category", this.registry.find(node.type()).orElseThrow().category().json());
			written.set("summary", summary);
			written.set("accounting", entry.accounting());
			written.set("timeseries", timeseries);
			written.set("config", RecordCodec.write(Objects.requireNonNull(configs.get(node.id()))));
			written.putArray("behaviour");
		}
		return nodes;
	}

	private static ArrayNode edges(SimulatedRun run, ArchitectureDocument resolved) {
		ArrayNode edges = Figures.NODES.arrayNode();
		Map<String, Set<RequestType>> flows = run.prepared().validation().edgeFlows();
		int used = run.usedBuckets();
		for (EdgeSpec spec : resolved.architecture().edges()) {
			EdgeRuntime edge = run.context().edge(spec.id());
			ObjectNode written = edges.addObject();
			written.put("id", spec.id());
			written.put("source", spec.source());
			written.put("sourcePort", spec.sourcePort());
			written.put("target", spec.target());
			ArrayNode types = written.putArray("requestTypes");
			spec.config().requestTypes().forEach((type) -> types.add(type.json()));
			ArrayNode flowTypes = written.putArray("flowTypes");
			flows.getOrDefault(spec.id(), Set.of()).stream().map(RequestType::json).sorted().forEach(flowTypes::add);
			written.put("attempts", edge.attempts().windowCount());
			written.put("attemptsTotal", edge.attempts().total());
			written.set("callRps", Figures.rounded(edge.attempts().windowRate()));
			written.put("retries", edge.retries().windowCount());
			written.put("timeouts", edge.timeouts().windowCount());
			written.put("failures", edge.failures().windowCount());
			ObjectNode series = written.putObject("timeseries");
			series.set("callRps", Figures.roundedSeries(edge.attempts().rateSeries(), used));
			series.set("timeoutRps", Figures.roundedSeries(edge.timeouts().rateSeries(), used));
			series.set("failureRps", Figures.roundedSeries(edge.failures().rateSeries(), used));
		}
		return edges;
	}

}
