package io.archsim.engine.calibration.otlp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.calibration.otlp.TraceSummary.Call;
import io.archsim.engine.calibration.otlp.TraceSummary.CpuSample;
import io.archsim.engine.numeric.PythonFloats;
import io.archsim.model.text.PythonText;

/**
 * Spans summarized as the Python engine's {@code summarize} summarizes them: a service's
 * handler time is a SERVER span's duration less the time its outgoing CLIENT and PRODUCER
 * spans cover; a database's read and write times are its CLIENT spans with
 * {@code db.system}, split by {@code db.operation} or the statement's first word.
 */
public final class TraceSummaries {

	/** The most parent links followed from one span (real traces are far shallower). */
	public static final int MAX_PARENT_DEPTH = 1000;

	/** Database operations that write. */
	private static final Set<String> WRITES = Set.of("INSERT", "UPDATE", "DELETE", "UPSERT", "MERGE", "SET", "PUT",
			"HSET", "DEL");

	private static final String SERVER = "SERVER";

	private static final String CLIENT = "CLIENT";

	private static final double NS_PER_MS = 1e6;

	private TraceSummaries() {
	}

	/**
	 * The summary of some spans.
	 * @param spans the spans
	 * @param cpuTime the attribute that holds a SERVER span's CPU time; {@code null} for
	 * none
	 * @return the summary; an empty one without spans
	 */
	public static TraceSummary summarize(List<Span> spans, @Nullable CpuTime cpuTime) {
		Summarizer summarizer = new Summarizer(spans);
		spans.forEach(summarizer::calls);
		spans.stream().filter((span) -> SERVER.equals(span.kind())).forEach((span) -> summarizer.server(span, cpuTime));
		return summarizer.summary();
	}

	/**
	 * The length of the union of intervals clipped to a span.
	 * @param intervals the intervals, start and end
	 * @param low the span's start
	 * @param high the span's end
	 * @return the covered length
	 */
	static long covered(List<long[]> intervals, long low, long high) {
		List<long[]> clipped = intervals.stream()
			.filter((interval) -> interval[1] > low && interval[0] < high)
			.map((interval) -> new long[] { Math.max(interval[0], low), Math.min(interval[1], high) })
			.sorted(Comparator.<long[]>comparingLong((interval) -> interval[0])
				.thenComparingLong((interval) -> interval[1]))
			.toList();
		long total = 0;
		long[] current = null;
		for (long[] interval : clipped) {
			if (current == null || interval[0] > current[1]) {
				total += (current != null) ? current[1] - current[0] : 0;
				current = interval.clone();
			}
			else {
				current[1] = Math.max(current[1], interval[1]);
			}
		}
		return total + ((current != null) ? current[1] - current[0] : 0);
	}

	/**
	 * The CPU time a span records in the attribute, in milliseconds.
	 * @param span the span
	 * @param cpuTime the attribute; {@code null} for none
	 * @return the CPU time; {@code null} without the attribute, a boolean, a value
	 * Python's {@code float()} refuses (a whole number beyond a float's range too, where
	 * Python's {@code OverflowError} failed the calibration), or one that is not finite
	 * and at least 0
	 */
	static @Nullable Double cpuMs(Span span, @Nullable CpuTime cpuTime) {
		if (cpuTime == null) {
			return null;
		}
		JsonNode raw = span.attr(cpuTime.attribute());
		if (raw.isMissingNode() || raw.isNull() || raw.isBoolean()) {
			return null;
		}
		double value;
		try {
			value = PythonFloats.of(raw);
		}
		catch (NumberFormatException ex) {
			return null;
		}
		return (Double.isFinite(value) && value >= 0) ? value * cpuTime.msPerUnit() : null;
	}

	/**
	 * A span's database.
	 * @param span a database call
	 * @return its {@code db.name}, else its {@code db.system}, as Python's {@code str()}
	 * writes it
	 */
	private static String database(Span span) {
		JsonNode name = span.attr("db.name");
		return PythonText.str(PythonText.truthy(name) ? name : span.attr("db.system"));
	}

	/**
	 * A database call's operation.
	 * @param span the call
	 * @return its {@code db.operation}, else its statement's first word, upper-cased
	 */
	private static String operation(Span span) {
		JsonNode operation = span.attr("db.operation");
		if (PythonText.truthy(operation)) {
			return PythonText.str(operation).toUpperCase(Locale.ROOT);
		}
		JsonNode statement = span.attr("db.statement");
		String text = PythonText.truthy(statement) ? PythonText.str(statement) : "";
		int space = text.indexOf(' ');
		return ((space < 0) ? text : text.substring(0, space)).toUpperCase(Locale.ROOT);
	}

	/**
	 * A span by its trace and its id.
	 *
	 * @param traceId the trace's id
	 * @param spanId the span's id
	 */
	private record SpanKey(String traceId, String spanId) {

	}

	/**
	 * The summary as the spans are walked.
	 */
	private static final class Summarizer {

		private final List<Span> spans;

		private final Map<SpanKey, Span> byId = new HashMap<>();

		private final Map<SpanKey, List<Span>> children = new HashMap<>();

		private final Map<SpanKey, List<long[]>> outgoing = new HashMap<>();

		private final Map<String, List<Double>> services = new LinkedHashMap<>();

		private final Map<String, List<Double>> dbReads = new LinkedHashMap<>();

		private final Map<String, List<Double>> dbWrites = new LinkedHashMap<>();

		private final Map<Call, Integer> calls = new LinkedHashMap<>();

		private final List<Double> roots = new ArrayList<>();

		private final Map<String, List<Double>> serverMs = new LinkedHashMap<>();

		private final Map<String, List<CpuSample>> cpu = new LinkedHashMap<>();

		private int parentLoops;

		Summarizer(List<Span> spans) {
			this.spans = spans;
			for (Span span : spans) {
				this.byId.put(new SpanKey(span.traceId(), span.spanId()), span);
				if (span.parentId() != null) {
					this.children
						.computeIfAbsent(new SpanKey(span.traceId(), span.parentId()), (key) -> new ArrayList<>())
						.add(span);
				}
			}
		}

		/**
		 * What a span adds to the outgoing calls, the databases, the call graph and the
		 * roots.
		 * @param span the span
		 */
		void calls(Span span) {
			if (CLIENT.equals(span.kind()) || "PRODUCER".equals(span.kind())) {
				outgoing(span);
			}
			if (CLIENT.equals(span.kind()) && PythonText.truthy(span.attr("db.system"))) {
				database(span);
			}
			if (CLIENT.equals(span.kind())) {
				callsToServers(span);
			}
			if (span.parentId() == null) {
				this.roots.add(span.ms());
			}
		}

		/**
		 * An outgoing call, counted against its SERVER ancestor in its own service.
		 * @param span a CLIENT or PRODUCER span
		 */
		private void outgoing(Span span) {
			Span server = serverOf(span);
			if (server != null && server.service().equals(span.service())) {
				this.outgoing
					.computeIfAbsent(new SpanKey(server.traceId(), server.spanId()), (key) -> new ArrayList<>())
					.add(new long[] { span.startNs(), span.endNs() });
			}
		}

		/**
		 * A database call: a read or a write of its database, and a call to it.
		 * @param span a CLIENT span with {@code db.system}
		 */
		private void database(Span span) {
			String database = TraceSummaries.database(span);
			(WRITES.contains(operation(span)) ? this.dbWrites : this.dbReads)
				.computeIfAbsent(database, (key) -> new ArrayList<>())
				.add(span.ms());
			this.calls.merge(new Call(span.service(), database), 1, Integer::sum);
		}

		/**
		 * Calls to other services: a CLIENT span's SERVER children in another service.
		 * @param span a CLIENT span
		 */
		private void callsToServers(Span span) {
			for (Span child : this.children.getOrDefault(new SpanKey(span.traceId(), span.spanId()), List.of())) {
				if (SERVER.equals(child.kind()) && !child.service().equals(span.service())) {
					this.calls.merge(new Call(span.service(), child.service()), 1, Integer::sum);
				}
			}
		}

		/**
		 * What a SERVER span adds: its duration, its own time and its CPU time.
		 * @param span a SERVER span
		 * @param cpuTime the attribute with its CPU time; {@code null} for none
		 */
		void server(Span span, @Nullable CpuTime cpuTime) {
			this.serverMs.computeIfAbsent(span.service(), (key) -> new ArrayList<>()).add(span.ms());
			long busy = covered(this.outgoing.getOrDefault(new SpanKey(span.traceId(), span.spanId()), List.of()),
					span.startNs(), span.endNs());
			double ownMs = Math.max(0, (span.endNs() - span.startNs()) - busy) / NS_PER_MS;
			this.services.computeIfAbsent(span.service(), (key) -> new ArrayList<>()).add(ownMs);
			Double cpuMs = cpuMs(span, cpuTime);
			if (cpuMs != null) {
				this.cpu.computeIfAbsent(span.service(), (key) -> new ArrayList<>()).add(new CpuSample(cpuMs, ownMs));
			}
		}

		/**
		 * The nearest SERVER ancestor. Parent links come from the file, so they may loop:
		 * each span is visited once, and the walk stops after {@link #MAX_PARENT_DEPTH}
		 * links.
		 * @param span the span
		 * @return the ancestor; {@code null} when there is none, the links loop or the
		 * walk stops
		 */
		@Nullable Span serverOf(Span span) {
			Set<String> seen = new HashSet<>(Set.of(span.spanId()));
			Span current = span;
			for (int i = 0; i < MAX_PARENT_DEPTH; i++) {
				String parent = (current != null) ? current.parentId() : null;
				if (current == null || parent == null) {
					return null;
				}
				if (!seen.add(parent)) {
					this.parentLoops++;
					return null;
				}
				current = this.byId.get(new SpanKey(current.traceId(), parent));
				if (current != null && SERVER.equals(current.kind())) {
					return current;
				}
			}
			return null;
		}

		TraceSummary summary() {
			long start = this.spans.stream().mapToLong(Span::startNs).min().orElse(0);
			long end = this.spans.stream().mapToLong(Span::endNs).max().orElse(0);
			return new TraceSummary(this.services, this.dbReads, this.dbWrites, this.calls, this.roots, this.serverMs,
					this.cpu, start, end, this.spans.size(), this.parentLoops);
		}

	}

}
