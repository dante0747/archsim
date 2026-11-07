package io.archsim.engine.calibration;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.fit.Fit;
import io.archsim.engine.calibration.fit.FitKind;
import io.archsim.engine.calibration.fit.LatencyFits;
import io.archsim.engine.calibration.otlp.CpuTime;
import io.archsim.engine.calibration.otlp.OtlpMapping;
import io.archsim.engine.calibration.otlp.OtlpSpans;
import io.archsim.engine.calibration.otlp.TraceSummaries;
import io.archsim.engine.calibration.otlp.TraceSummary;
import io.archsim.engine.calibration.otlp.TraceSummary.CpuSample;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.provenance.InputPaths;
import io.archsim.engine.text.PythonStrings;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * A design calibrated from OpenTelemetry traces, as the Python engine's
 * {@code calibrate_otlp} calibrates it: each service's handler time without its
 * downstream calls, each database's read and write times, and, where spans record it,
 * each service's CPU share; services and databases matched to parts by the mapping or by
 * their ids and labels. The calibrated design is replayed at the traced request rate and
 * compared with each service's traced latency.
 */
public final class OtlpCalibration {

	private static final String LABEL = "OpenTelemetry traces";

	private static final String CPU_SHARE = "cpuShare";

	private static final int SHARE_DIGITS = 4;

	private static final Set<String> SERVICES = Set.of("service");

	private static final Set<String> SERVICES_AND_DATABASES = Set.of("service", "database");

	private final ReplaySimulator simulator;

	private final Clock clock;

	/**
	 * A calibration.
	 * @param simulator what the calibrated design is replayed with
	 * @param clock when the provenance says the inputs were fitted
	 */
	public OtlpCalibration(ReplaySimulator simulator, Clock clock) {
		this.simulator = simulator;
		this.clock = clock;
	}

	/**
	 * Calibrates a design.
	 * @param request the design, the traces, the mapping and how
	 * @return the calibrated design, each input set and what was noticed; the design as
	 * it was when the traces have no spans
	 * @throws IllegalArgumentException in the Python engine's words when the traces are
	 * not an OTLP export, a span lacks a key or a time, or the mapping names a part the
	 * design does not have
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design, or the
	 * calibrated design, is not valid
	 */
	public CalibrationOutcome calibrate(OtlpRequest request) {
		OtlpMapping mapping = (request.mapping() != null) ? request.mapping()
				: new OtlpMapping(Map.of(), Map.of(), null);
		CpuTime cpuTime = (mapping.cpuTime() != null) ? mapping.cpuTime().toCpuTime() : null;
		TraceSummary summary = TraceSummaries.summarize(OtlpSpans.parse(request.traces()), cpuTime);
		String range = CalibrationTimes.ofNanoseconds(summary.startNs()) + "/"
				+ CalibrationTimes.ofNanoseconds(summary.endNs());
		CalibrationRun run = new CalibrationRun(request.document(), this.clock, LABEL, range);
		if (summary.spans() == 0) {
			run.note("the file has no spans");
			return run.unchanged();
		}
		if (summary.parentLoops() > 0) {
			run.note(summary.parentLoops() + " spans have parent links that loop back on themselves: their time is "
					+ "not subtracted from any service");
		}
		OtlpRun otlp = new OtlpRun(run, mapping, request.minSamples());
		otlp.services(summary);
		if (cpuTime != null) {
			otlp.cpuShares(summary, cpuTime.attribute());
		}
		otlp.databases(summary);
		otlp.missingLinks(summary);
		run.stamp();
		OtlpReplay.Result replay = new OtlpReplay(run, otlp).check(summary, this.simulator, request.limits());
		return run.complete(record(run, replay));
	}

	/**
	 * The calibration record: when, from what, how many inputs, the replay and the first
	 * notes.
	 * @param run the calibration
	 * @param replay the replay's checks and digest
	 * @return {@code {at, source, timeRange, inputs, replay, replayDigest, notes}}
	 */
	private static ObjectNode record(CalibrationRun run, OtlpReplay.Result replay) {
		ObjectNode record = run.document()
			.objectNode()
			.put("at", run.now())
			.put("source", LABEL)
			.put("timeRange", run.timeRange())
			.put("inputs", run.inputs());
		record.set("replay", replay.checks());
		record.put("replayDigest", replay.digest());
		ArrayNode notes = record.putArray("notes");
		run.notes().stream().limit(PrometheusCalibration.RECORD_NOTES).forEach(notes::add);
		return record;
	}

	/**
	 * One calibration from traces as it runs: the run and how services and databases are
	 * matched to parts.
	 */
	static final class OtlpRun {

		private final CalibrationRun run;

		private final OtlpMapping mapping;

		private final int minSamples;

		OtlpRun(CalibrationRun run, OtlpMapping mapping, int minSamples) {
			this.run = run;
			this.mapping = mapping;
			this.minSamples = minSamples;
		}

		void services(TraceSummary summary) {
			sorted(summary.services()).forEach((service, samples) -> {
				String nodeId = match(service, this.mapping.services(), SERVICES);
				if (nodeId == null) {
					this.run.note("service '" + service + "' (" + samples.size()
							+ " spans) matches no service part: map it in the mapping's 'services'");
					return;
				}
				apply(nodeId, "serviceTimeMs", samples, "SERVER spans of " + service + ", minus their outgoing calls");
			});
		}

		void databases(TraceSummary summary) {
			Set<String> databases = new TreeSet<>(PythonStrings::compare);
			databases.addAll(summary.dbReads().keySet());
			databases.addAll(summary.dbWrites().keySet());
			for (String database : databases) {
				String nodeId = match(database, this.mapping.databases(), Set.of("database"));
				if (nodeId == null) {
					this.run.note("database '" + database
							+ "' matches no database part: map it in the mapping's 'databases'");
					continue;
				}
				List<Double> reads = summary.dbReads().get(database);
				if (reads != null && !reads.isEmpty()) {
					apply(nodeId, "readServiceTimeMs", reads, "CLIENT spans to " + database + " (reads)");
				}
				List<Double> writes = summary.dbWrites().get(database);
				if (writes != null && !writes.isEmpty()) {
					apply(nodeId, "writeServiceTimeMs", writes, "CLIENT spans to " + database + " (writes)");
				}
			}
		}

		/**
		 * Notes each call the traces show between parts the design does not link.
		 * @param summary the traces' summary
		 */
		void missingLinks(TraceSummary summary) {
			Set<Link> links = new HashSet<>();
			this.run.document()
				.path("architecture")
				.path("edges")
				.forEach((edge) -> links.add(new Link(edge.path("source").asString(), edge.path("target").asString())));
			Map<String, String> both = new TreeMap<>(this.mapping.services());
			both.putAll(this.mapping.databases());
			summary.calls()
				.entrySet()
				.stream()
				.sorted((first, second) -> compareCalls(first.getKey(), second.getKey()))
				.forEach((call) -> {
					String from = match(call.getKey().caller(), this.mapping.services(), SERVICES);
					String to = match(call.getKey().callee(), both, SERVICES_AND_DATABASES);
					if (from != null && to != null && !links.contains(new Link(from, to))) {
						this.run.note("the traces show " + call.getKey().caller() + " calling " + call.getKey().callee()
								+ " (" + call.getValue() + " calls) but the design has no link " + from + " → " + to);
					}
				});
		}

		void cpuShares(TraceSummary summary, String attribute) {
			if (summary.cpu().isEmpty()) {
				this.run
					.note("no SERVER span carries the CPU-time attribute '" + attribute + "': CPU shares unchanged");
			}
			sorted(summary.cpu()).forEach((service, samples) -> {
				String nodeId = match(service, this.mapping.services(), SERVICES);
				if (nodeId != null) {
					cpuShare(nodeId, "SERVER spans of " + service + ": '" + attribute + "' ÷ own time", samples);
				}
			});
		}

		/**
		 * Sets a service's CPU share from its spans' CPU time over their own time, when
		 * there are enough spans.
		 * @param nodeId the part
		 * @param what the spans, in words
		 * @param samples each span's CPU time and own time
		 */
		private void cpuShare(String nodeId, String what, List<CpuSample> samples) {
			if (samples.size() < this.minSamples) {
				this.run.note(what + ": only " + samples.size() + " spans (need " + this.minSamples + "); " + nodeId
						+ ".cpuShare unchanged");
				return;
			}
			double cpuMs = FloatSum.of(samples.stream().mapToDouble(CpuSample::cpuMs));
			double ownMs = FloatSum.of(samples.stream().mapToDouble(CpuSample::ownMs));
			if (ownMs <= 0) {
				return;
			}
			double raw = cpuMs / ownMs;
			String note = PythonFormat.format(cpuMs / samples.size(), ".4g") + " ms of CPU over "
					+ PythonFormat.format(ownMs / samples.size(), ".4g") + " ms of own time per span, "
					+ PythonFormat.format(samples.size(), ",") + " spans";
			if (raw > 1) {
				note += "; more CPU than own time (CPU outside the handler: parsing, drivers, garbage collection): the "
						+ "share is at most 1, so the model under-counts CPU";
				this.run.note(nodeId + ".cpuShare: " + note);
			}
			ObjectNode node = node(nodeId);
			ObjectNode config = CalibrationRun.config(node);
			JsonNode before = config.has(CPU_SHARE) ? config.get(CPU_SHARE) : config.numberNode(1.0);
			JsonNode share = config.numberNode(PythonRound.round(Math.min(1.0, raw), SHARE_DIGITS));
			Change change = new Change(InputPaths.node(nodeId, CPU_SHARE), CalibrationRun.label(node) + ": cpuShare",
					before, share, null, what);
			config.set(CPU_SHARE, share);
			this.run.measured(this.run.record(what, null, CalibrationRun.first(note, PrometheusRun.NOTE_LENGTH)),
					change);
		}

		/**
		 * Sets a part's time from spans, when there are enough.
		 * @param nodeId the part
		 * @param key the setting
		 * @param samples the spans' times, in milliseconds
		 * @param what the spans, in words
		 */
		private void apply(String nodeId, String key, List<Double> samples, String what) {
			ObjectNode node = node(nodeId);
			if (samples.size() < this.minSamples) {
				this.run.note(what + ": only " + samples.size() + " spans (need " + this.minSamples + "); " + nodeId
						+ "." + key + " unchanged");
				return;
			}
			Fit fit = LatencyFits.fitSamples(samples, FitKind.LOGNORMAL);
			ObjectNode config = CalibrationRun.config(node);
			JsonNode before = config.has(key) ? config.get(key) : config.nullNode();
			Change change = new Change(InputPaths.node(nodeId, key), CalibrationRun.label(node) + ": " + key, before,
					fit.distribution(), fit.error(), what);
			config.set(key, fit.distribution());
			this.run.measured(
					this.run.spansRecord(what, fit.error(), PythonFormat.format(samples.size(), ",") + " spans"),
					change);
		}

		OtlpMapping mapping() {
			return this.mapping;
		}

		/**
		 * A part the mapping or a match named.
		 * @param nodeId its id
		 * @return the part
		 * @throws IllegalArgumentException as Python's {@code KeyError} when the design
		 * has no such part
		 */
		ObjectNode node(String nodeId) {
			ObjectNode node = this.run.node(nodeId);
			if (node == null) {
				throw new IllegalArgumentException(PythonText.repr(nodeId));
			}
			return node;
		}

		/**
		 * The part a service or a database is: the mapping's, else a part of one of the
		 * types whose id or label is the name, in any case.
		 * @param name the service's or the database's name
		 * @param explicit the mapping's names
		 * @param types the types it may be
		 * @return the part's id; {@code null} when none is
		 */
		@Nullable String match(String name, Map<String, String> explicit, Set<String> types) {
			if (explicit.containsKey(name)) {
				return explicit.get(name);
			}
			String low = name.toLowerCase(Locale.ROOT);
			for (JsonNode node : this.run.document().path("architecture").path("nodes")) {
				JsonNode label = node.path("label");
				String labelText = PythonText.truthy(label) ? PythonText.str(label) : "";
				if (types.contains(node.path("type").asString())
						&& (node.path("id").asString().toLowerCase(Locale.ROOT).equals(low)
								|| labelText.toLowerCase(Locale.ROOT).equals(low))) {
					return node.path("id").asString();
				}
			}
			return null;
		}

		private static <T> Map<String, T> sorted(Map<String, T> map) {
			Map<String, T> sorted = new TreeMap<>(PythonStrings::compare);
			sorted.putAll(map);
			return sorted;
		}

		private static int compareCalls(TraceSummary.Call first, TraceSummary.Call second) {
			int byCaller = PythonStrings.compare(first.caller(), second.caller());
			return (byCaller != 0) ? byCaller : PythonStrings.compare(first.callee(), second.callee());
		}

		/**
		 * A link the design has.
		 *
		 * @param source its source part's id
		 * @param target its target part's id
		 */
		private record Link(String source, String target) {

		}

	}

}
