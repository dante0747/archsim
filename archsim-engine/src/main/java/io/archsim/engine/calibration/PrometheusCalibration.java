package io.archsim.engine.calibration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.DoublePredicate;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.CpuPerRequestSource;
import io.archsim.engine.calibration.mapping.NodeSource;
import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.mapping.WorkingSetSource;
import io.archsim.engine.calibration.prometheus.PrometheusClient;
import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;
import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.component.ComponentRegistry;

/**
 * A design calibrated from Prometheus through a mapping, as the Python engine's
 * {@code calibrate_prometheus} calibrates it: each mapped input set (histograms fitted to
 * distributions, gauges, hit ratios, link latency, the arrival rate as a {@code trace}
 * pattern, CPU per request as the CPU share, the working set as memory), each with its
 * provenance; then the calibrated design simulated and compared with what was observed.
 * The result is a new design; the input is not changed.
 */
public final class PrometheusCalibration {

	/** The most notes the calibration record keeps. */
	static final int RECORD_NOTES = 50;

	private final ComponentRegistry registry;

	private final ComponentFacts facts;

	private final ReplaySimulator simulator;

	private final Clock clock;

	/**
	 * A calibration over these types.
	 * @param registry the design's types
	 * @param simulator what the calibrated design is replayed with
	 * @param clock when the provenance says the inputs were fitted
	 */
	public PrometheusCalibration(ComponentRegistry registry, ReplaySimulator simulator, Clock clock) {
		this.registry = registry;
		this.facts = new ComponentFacts(registry);
		this.simulator = simulator;
		this.clock = clock;
	}

	/**
	 * Calibrates a design.
	 * @param request the design, the mapping, the client and how
	 * @return the calibrated design, each input set and what was noticed
	 * @throws IllegalArgumentException in the Python engine's words when there is no
	 * range or it ends before it starts
	 * @throws io.archsim.engine.calibration.prometheus.PrometheusException when a query
	 * fails or returns what cannot be used
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design, or the
	 * calibrated design, is not valid
	 */
	public CalibrationOutcome calibrate(PrometheusRequest request) {
		List<String> notes = new ArrayList<>();
		TimeRange range = range(request, notes);
		double start = CalibrationTimes.parse(range.start());
		double end = CalibrationTimes.parse(range.end());
		if (end <= start) {
			throw new IllegalArgumentException("the range ends before it starts");
		}
		PrometheusClient client = request.client();
		CalibrationRun run = new CalibrationRun(request.document(), this.clock, client.source(),
				range.start() + "/" + range.end());
		notes.forEach(run::note);
		PrometheusRun context = new PrometheusRun(run, client, range, start, end, this.registry, this.facts);
		CalibrationMapping mapping = request.mapping();
		SettingSources.calibrate(context, mapping);
		LinkLatencies.calibrate(context, mapping);
		WorkloadCalibration.calibrate(context, mapping);
		Map<String, Observed> observed = resources(context, mapping);
		run.stamp();
		return run.complete(record(context, request, observed));
	}

	/**
	 * The range read: a recording's own, else the one asked for, else the mapping's.
	 * @param request the request
	 * @param notes where a range a recording overrides is noted
	 * @return the range
	 * @throws IllegalArgumentException in the Python engine's words when there is none
	 */
	private static TimeRange range(PrometheusRequest request, List<String> notes) {
		TimeRange given = (request.range() != null) ? request.range() : request.mapping().range();
		Optional<TimeRange> recorded = request.client().recordedRange();
		if (recorded.isPresent()) {
			TimeRange covered = recorded.get();
			if (given != null && (!given.start().equals(covered.start()) || !given.end().equals(covered.end()))) {
				notes.add("the recording covers " + covered.start() + "/" + covered.end() + "; the range "
						+ given.start() + "/" + given.end() + " was not used");
			}
			return covered;
		}
		if (given == null) {
			throw new IllegalArgumentException("no time range: pass one or set 'range' in the mapping");
		}
		return given;
	}

	/**
	 * The parts' CPU and memory, last: a CPU share depends on the calibrated service time
	 * and read mix.
	 * @param run the calibration
	 * @param mapping the mapping
	 * @return what each fitted part used, for the replay
	 */
	private static Map<String, Observed> resources(PrometheusRun run, CalibrationMapping mapping) {
		Map<String, Observed> observed = new LinkedHashMap<>();
		mapping.nodes().forEach((nodeId, sources) -> {
			ObjectNode node = run.run().node(nodeId);
			if (node != null) {
				sources.values().forEach((source) -> resource(run, node, source, observed));
			}
		});
		return observed;
	}

	/**
	 * Fits a part's CPU or memory from one of its sources, and adds what it used to what
	 * the part was observed to use; other sources were fitted before.
	 * @param run the calibration
	 * @param node the part
	 * @param source one of its sources
	 * @param observed what each fitted part used, so far
	 */
	private static void resource(PrometheusRun run, ObjectNode node, NodeSource source,
			Map<String, Observed> observed) {
		String nodeId = node.path("id").asString();
		Observed before = observed.getOrDefault(nodeId, Observed.NONE);
		if (source instanceof CpuPerRequestSource cpu) {
			List<Point> seen = CpuCalibration.calibrate(run, node, cpu);
			if (!seen.isEmpty()) {
				observed.put(nodeId, before.withCpu(seen));
			}
		}
		else if (source instanceof WorkingSetSource memory) {
			List<Point> seen = MemoryCalibration.calibrate(run, node, memory);
			if (!seen.isEmpty()) {
				observed.put(nodeId, before.withMemory(seen));
			}
		}
	}

	/**
	 * The calibration's record, with the replay's checks when it replays.
	 * @param run the calibration
	 * @param request the request
	 * @param observed what each fitted part used
	 * @return {@code {at, source, timeRange, inputs, replay, replayDigest?, notes}}
	 */
	private ObjectNode record(PrometheusRun run, PrometheusRequest request, Map<String, Observed> observed) {
		CalibrationRun calibration = run.run();
		ObjectNode record = calibration.document()
			.objectNode()
			.put("at", calibration.now())
			.put("source", calibration.source())
			.put("timeRange", calibration.timeRange())
			.put("inputs", calibration.inputs());
		record.putArray("replay");
		record.set("notes", firstNotes(calibration));
		Optional<DoublePredicate> keep = PrometheusReplay.replayed(request.mapping(),
				calibration.document().path("workload"), run.start(), run.end());
		if (!observed.isEmpty() && keep.isEmpty()) {
			calibration.note("CPU and memory are replayed only when the workload is calibrated from the same range "
					+ "(workload.rps): the run's own traffic would not match what they used");
			record.set("notes", firstNotes(calibration));
		}
		boolean resourcesReplayed = !observed.isEmpty() && keep.isPresent();
		if (request.replay() && (request.mapping().replay() != null || resourcesReplayed)) {
			PrometheusReplay.Result result = new PrometheusReplay(run, this.simulator, request.limits())
				.check(request.mapping().replay(), keep.isPresent() ? observed : Map.of(), keep.orElse(null));
			record.set("replay", result.checks());
			record.put("replayDigest", result.digest());
		}
		return record;
	}

	private static ArrayNode firstNotes(CalibrationRun run) {
		ArrayNode notes = run.document().arrayNode();
		run.notes().stream().limit(RECORD_NOTES).forEach(notes::add);
		return notes;
	}

}
