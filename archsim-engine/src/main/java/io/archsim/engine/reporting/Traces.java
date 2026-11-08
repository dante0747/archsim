package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.report.FailureReason;
import io.archsim.engine.runtime.Span;
import io.archsim.engine.runtime.TracedRequest;

/**
 * A report's {@code traces}: a readable sample of the traced requests (evenly spread
 * ones, the slowest, the median and failures), each with its steps relative to its start.
 */
final class Traces {

	private static final int SLOWEST = 8;

	private static final int FAILED = 8;

	private static final int SAMPLE = 18;

	private static final int TIME_DIGITS = 3;

	private final List<TracedRequest> traced;

	private final Labels labels;

	private final Map<TracedRequest, TreeSet<String>> picked = new LinkedHashMap<>();

	Traces(List<TracedRequest> traces, Labels labels) {
		List<TracedRequest> measured = traces.stream().filter((trace) -> trace.request().arrival().measured()).toList();
		this.traced = measured.isEmpty() ? traces : measured;
		this.labels = labels;
	}

	ArrayNode write() {
		List<TracedRequest> served = this.traced.stream()
			.filter((trace) -> trace.outcome().ok())
			.sorted(Comparator.comparingDouble(Traces::duration))
			.toList();
		List<TracedRequest> failed = this.traced.stream().filter((trace) -> !trace.outcome().ok()).toList();
		served.subList(Math.max(0, served.size() - SLOWEST), served.size()).forEach((trace) -> tag(trace, "slowest"));
		if (!served.isEmpty()) {
			tag(served.get(served.size() / 2), "median");
		}
		take(byStart(failed), FAILED, "failed");
		take(byStart(served), SAMPLE, "sample");
		ArrayNode traces = Figures.NODES.arrayNode();
		byStart(new ArrayList<>(this.picked.keySet())).forEach((trace) -> traces.add(written(trace)));
		return traces;
	}

	private void take(List<TracedRequest> items, int wanted, String tag) {
		if (items.isEmpty() || wanted <= 0) {
			return;
		}
		double step = Math.max(1, (double) items.size() / wanted);
		for (int index = 0; index < Math.min(wanted, items.size()); index++) {
			tag(items.get((int) (index * step)), tag);
		}
	}

	private void tag(TracedRequest trace, String tag) {
		this.picked.computeIfAbsent(trace, (ignored) -> new TreeSet<>()).add(tag);
	}

	private ObjectNode written(TracedRequest trace) {
		double start = trace.request().arrival().sentAt();
		ObjectNode written = Figures.NODES.objectNode();
		written.put("id", trace.request().arrival().id());
		written.put("op", trace.request().operation().json());
		written.put("client", trace.clientId());
		written.put("startS", PythonRound.round(start / Figures.MS_PER_SECOND, TIME_DIGITS));
		written.put("durationMs", PythonRound.round(trace.end() - start, TIME_DIGITS));
		written.put("status", status(trace));
		FailureReason reason = trace.outcome().ok() ? null : trace.outcome().reason();
		written.put("reason", (reason != null) ? reason.json() : null);
		written.put("failedAt", trace.outcome().ok() ? null : trace.outcome().nodeId());
		written.put("attempts", trace.attempts());
		ArrayNode tags = written.putArray("tags");
		Objects.requireNonNull(this.picked.get(trace)).forEach(tags::add);
		ArrayNode spans = written.putArray("spans");
		trace.spans()
			.stream()
			.sorted(Comparator.comparingDouble(Span::start).thenComparingInt(Span::depth))
			.forEach((span) -> spans.add(span(span, start)));
		return written;
	}

	private ObjectNode span(Span span, double start) {
		ObjectNode written = Figures.NODES.objectNode();
		written.put("nodeId", span.nodeId());
		written.put("label", this.labels.of(span.nodeId()));
		written.put("edgeId", span.edgeId());
		written.put("kind", span.kind().json());
		written.put("startMs", PythonRound.round(span.start() - start, TIME_DIGITS));
		written.put("endMs", PythonRound.round(span.end() - start, TIME_DIGITS));
		written.put("depth", span.depth());
		written.put("detail", span.detail());
		return written;
	}

	private static String status(TracedRequest trace) {
		if (trace.outcome().ok()) {
			return "ok";
		}
		return trace.outcome().failedFor(FailureReason.CLIENT_TIMEOUT) ? "timeout" : "error";
	}

	private static double duration(TracedRequest trace) {
		return trace.end() - trace.request().arrival().sentAt();
	}

	private static List<TracedRequest> byStart(List<TracedRequest> traces) {
		return traces.stream()
			.sorted(Comparator.comparingDouble((TracedRequest trace) -> trace.request().arrival().sentAt()))
			.toList();
	}

}
