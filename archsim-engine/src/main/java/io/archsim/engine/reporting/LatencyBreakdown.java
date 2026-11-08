package io.archsim.engine.reporting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.runtime.Span;
import io.archsim.engine.runtime.SpanKind;
import io.archsim.engine.runtime.TracedRequest;

/**
 * Where a request's time goes: the mean time a measured, served request spent in each
 * part (its handling less its own downstream calls), on the network and backing off, over
 * the traced requests.
 */
final class LatencyBreakdown {

	private static final String NETWORK = "__network";

	private static final String BACKOFF = "__backoff";

	/** How far past a step's edges another may lie and still count inside it. */
	private static final double SLACK_MS = 1e-9;

	private static final int MEAN_DIGITS = 3;

	private final Map<String, Double> totals = new LinkedHashMap<>();

	private final int served;

	LatencyBreakdown(List<TracedRequest> traces) {
		List<TracedRequest> measuredServed = traces.stream()
			.filter((trace) -> trace.outcome().ok() && trace.request().arrival().measured())
			.toList();
		this.served = measuredServed.size();
		measuredServed.forEach(this::add);
	}

	/**
	 * How many traced requests the breakdown covers.
	 * @return the measured requests traced that were served
	 */
	int served() {
		return this.served;
	}

	ArrayNode write(Labels labels) {
		ArrayNode rows = Figures.NODES.arrayNode();
		if (this.served == 0) {
			return rows;
		}
		double sum = FloatSum.of(this.totals.values().stream().mapToDouble(Double::doubleValue));
		double grand = (sum != 0) ? sum : 1.0;
		List<Map.Entry<String, Double>> ordered = new ArrayList<>(this.totals.entrySet());
		ordered.sort(Map.Entry.comparingByValue((first, second) -> Double.compare(-first, -second)));
		for (Map.Entry<String, Double> total : ordered) {
			String key = total.getKey();
			ObjectNode row = rows.addObject();
			row.put("nodeId", key.startsWith("__") ? null : key);
			row.put("label", label(key, labels));
			row.put("meanMs", PythonRound.round(total.getValue() / this.served, MEAN_DIGITS));
			row.put("share", PythonRound.round(total.getValue() / grand, Figures.DIGITS));
		}
		return rows;
	}

	private void add(TracedRequest trace) {
		List<Span> spans = trace.spans().stream().filter((span) -> span.end() <= trace.end() + SLACK_MS).toList();
		for (Span span : spans) {
			double duration = span.end() - span.start();
			if (span.kind() == SpanKind.NETWORK) {
				this.totals.merge(NETWORK, duration, Double::sum);
			}
			else if (span.kind() == SpanKind.BACKOFF) {
				this.totals.merge(BACKOFF, duration, Double::sum);
			}
			else if (span.kind() == SpanKind.HANDLE && span.nodeId() != null) {
				double own = Math.max(0.0, duration - inner(span, spans));
				this.totals.merge(span.nodeId(), own, Double::sum);
			}
		}
	}

	// the time a handling step spent in its own calls: their network legs and their
	// callees' handling, one level down
	private static double inner(Span handle, List<Span> spans) {
		return FloatSum.of(spans.stream()
			.filter((span) -> span.start() >= handle.start() - SLACK_MS && span.end() <= handle.end() + SLACK_MS)
			.filter((span) -> isOwnCall(handle, span))
			.mapToDouble((span) -> span.end() - span.start()));
	}

	private static boolean isOwnCall(Span handle, Span span) {
		if (span.kind() == SpanKind.NETWORK) {
			return span.depth() == handle.depth();
		}
		return span.kind() == SpanKind.HANDLE && span.depth() == handle.depth() + 1;
	}

	private static String label(String key, Labels labels) {
		return switch (key) {
			case NETWORK -> "Network";
			case BACKOFF -> "Retry backoff";
			default -> labels.name(key);
		};
	}

}
