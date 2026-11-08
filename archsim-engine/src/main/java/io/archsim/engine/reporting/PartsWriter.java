package io.archsim.engine.reporting;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.explain.Formula;
import io.archsim.engine.explain.QueueingFormulas;
import io.archsim.engine.findings.Classification;
import io.archsim.engine.findings.RunAnalysis;
import io.archsim.engine.report.ReportNode;
import io.archsim.engine.report.ReportWindow;
import io.archsim.engine.runtime.EdgeRuntime;
import io.archsim.engine.runtime.RuntimeComponent;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.document.EdgeSpec;
import io.archsim.model.document.NodeSpec;
import io.archsim.model.document.RequestType;

/**
 * Writes a report's parts and links: each part's figures, its formulas and its status,
 * and each link's calls.
 */
final class PartsWriter {

	private final ComponentRegistry registry;

	PartsWriter(ComponentRegistry registry) {
		this.registry = registry;
	}

	/**
	 * Each part of a run, with its formulas.
	 * @param run the finished run
	 * @return the parts, in the design's order
	 */
	ArrayNode nodes(SimulatedRun run) {
		ArrayNode nodes = Figures.NODES.arrayNode();
		Map<String, Record> configs = run.prepared().validation().configs();
		for (NodeSpec node : run.prepared().resolved().architecture().nodes()) {
			RuntimeComponent component = run.context().component(node.id());
			ComponentSpec<?> spec = this.registry.find(node.type()).orElseThrow();
			NodeEntry entry = new NodeEntry(component);
			NodeSeries series = new NodeSeries(component, run.usedBuckets());
			ObjectNode summary = entry.summary();
			ObjectNode timeseries = series.write();
			summary.set("latencyMs", Figures.latency(component.metrics().residence().window().summary()));
			component.extraSummary().forEach((name, value) -> summary.set(name, number(value)));
			summary.set("peakUtilization", series.peakUtilization(timeseries));
			ObjectNode written = nodes.addObject();
			written.put("id", node.id());
			written.put("type", node.type());
			written.put("label", Labels.name(node));
			written.put("category", spec.category().json());
			written.set("summary", summary);
			written.set("accounting", entry.accounting());
			written.set("timeseries", timeseries);
			written.set("config", RecordCodec.write(Objects.requireNonNull(configs.get(node.id()))));
			ArrayNode behaviour = written.putArray("behaviour");
			component.behaviour().forEach((trait) -> behaviour.add(RecordCodec.write(trait)));
			written.set("explain",
					spec.isTrafficSource() ? Figures.NODES.arrayNode() : formulas(written, component, run));
		}
		return nodes;
	}

	/**
	 * Gives each part its status and the reasons for it, read from the report as written
	 * so far, as the Python engine's analysis did before it wrote its findings. The
	 * analysis of the findings classifies the parts again, as the Python's did; the
	 * statuses are written here because the result's digest covers them.
	 * @param report the report, with its window, time series and parts
	 */
	void writeStatuses(ObjectNode report) {
		JsonNode written = report.get("window");
		ReportWindow window = new ReportWindow(written.get("warmupS").doubleValue(),
				written.get("durationS").doubleValue(), written.get("measuredS").doubleValue());
		ArrayNode nodes = (ArrayNode) report.get("nodes");
		List<ReportNode> parts = nodes.valueStream().map(ReportNode::read).toList();
		Map<String, Classification> statuses = RunAnalysis.classify(window,
				report.get("timeseries").get("bucketS").doubleValue(), parts);
		for (int index = 0; index < nodes.size(); index++) {
			ObjectNode node = (ObjectNode) nodes.get(index);
			Classification status = Objects.requireNonNull(statuses.get(parts.get(index).id()));
			node.put("status", status.status().json());
			ArrayNode reasons = node.putArray("statusReasons");
			status.reasons().forEach(reasons::add);
		}
	}

	/**
	 * Each link of a run.
	 * @param run the finished run
	 * @return the links, in the design's order
	 */
	ArrayNode edges(SimulatedRun run) {
		ArrayNode edges = Figures.NODES.arrayNode();
		Map<String, Set<RequestType>> flows = run.prepared().validation().edgeFlows();
		int used = run.usedBuckets();
		for (EdgeSpec spec : run.prepared().resolved().architecture().edges()) {
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

	// the queueing formulas of a part that serves requests (its slots' when its
	// utilization is its busy slots), then its own
	private static ArrayNode formulas(ObjectNode node, RuntimeComponent component, SimulatedRun run) {
		ReportNode written = ReportNode.read(node);
		ArrayNode formulas = Figures.NODES.arrayNode();
		List<Formula> queueing = Capacity.poolBased(component) ? QueueingFormulas.ofSlots(written, run.windowSeconds())
				: QueueingFormulas.of(written, run.windowSeconds());
		queueing.forEach((formula) -> formulas.add(RecordCodec.write(formula)));
		component.explain(written.summary()).forEach((formula) -> formulas.add(RecordCodec.write(formula)));
		return formulas;
	}

	// a count as an integer, any other figure as a float, as the digests hash them
	private static JsonNode number(@Nullable Number value) {
		return switch (value) {
			case null -> Figures.NODES.nullNode();
			case Double figure -> Figures.NODES.numberNode(figure);
			case Long count -> Figures.NODES.numberNode(count);
			case Integer count -> Figures.NODES.numberNode(count);
			default -> throw new IllegalArgumentException(
					"a part's own figure is a Long, an Integer or a Double; got a " + value.getClass().getSimpleName());
		};
	}

}
