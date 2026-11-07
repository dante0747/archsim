package io.archsim.engine.calibration;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.RateSource;
import io.archsim.engine.calibration.mapping.WorkloadInput;
import io.archsim.engine.calibration.prometheus.PrometheusSeries;
import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.provenance.InputPaths;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The workload's arrival rate, as the Python engine's {@code calibrate_prometheus} sets
 * it: the observed rate's mean as the baseline load, and the observed curve as a
 * {@code trace} pattern fitted into the run's existing duration. The run's duration and
 * warm-up are not changed, so calibrating never makes later runs longer.
 */
final class WorkloadCalibration {

	/** The most points a trace keeps; more are averaged in groups. */
	static final int MAX_TRACE_POINTS = 300;

	/** The highest rate a workload takes. */
	private static final double MAX_RATE = 100_000.0;

	private static final double DEFAULT_DURATION_S = 60;

	private static final int RATE_DIGITS = 4;

	private static final double SECONDS_PER_HOUR = 3600;

	private static final double SECONDS_PER_MINUTE = 60;

	/** From this many seconds a span is written in hours. */
	private static final double HOURS_FROM = 7200;

	/** From this many seconds a span is written in minutes. */
	private static final double MINUTES_FROM = 120;

	private static final double SAME_SCALE = 1e-6;

	private WorkloadCalibration() {
	}

	/**
	 * Sets the workload's rate, and its pattern when the mapping asks for the trace.
	 * @param run the calibration
	 * @param mapping the mapping
	 */
	static void calibrate(PrometheusRun run, CalibrationMapping mapping) {
		RateSource source = mapping.workload().get(WorkloadInput.RPS);
		if (source == null) {
			return;
		}
		List<Point> points = PrometheusSeries.summed(run.query(source.rate()));
		if (points.isEmpty()) {
			run.run().note("the arrival-rate query returned no data: workload unchanged");
			return;
		}
		ObjectNode workload = run.run().document().withObjectProperty("workload");
		JsonNode before = workload.has("rps") ? workload.get("rps") : workload.nullNode();
		double mean = FloatSum.of(points.stream().mapToDouble(Point::value)) / points.size();
		workload.put("rps", PythonRound.round(Math.min(mean, MAX_RATE), RATE_DIGITS));
		run.run()
			.measured(run.run().record(source.rate(), null, "mean of the observed rate"),
					new Change(InputPaths.workload("rps"), "Workload: baseline load", before, workload.get("rps"), null,
							source.rate()));
		if (source.trace()) {
			pattern(run, source.rate(), points, workload);
		}
	}

	/**
	 * Sets the workload's pattern to the observed rate's trace.
	 * @param run the calibration
	 * @param query the arrival-rate query
	 * @param points the observed rate, by time
	 * @param workload the design's workload
	 */
	private static void pattern(PrometheusRun run, String query, List<Point> points, ObjectNode workload) {
		JsonNode pattern = workload.path("pattern");
		JsonNode kind = PythonText.truthy(pattern) ? pattern.path("kind") : workload.missingNode();
		JsonNode previous = kind.isMissingNode() ? workload.stringNode("constant") : kind;
		ObjectNode trace = trace(points, run.start(), run.end(), workload);
		String note = trace.path("source").asString();
		workload.set("pattern", trace);
		run.run()
			.measured(run.run().record(query, null, note), new Change(InputPaths.workload("pattern"),
					"Workload: traffic pattern", previous, workload.stringNode("trace"), null, query));
		run.run().note(note);
	}

	/**
	 * The observed rate as a {@code trace} pattern fitted into the run's duration: the
	 * curve keeps its shape, compressed or stretched in time.
	 * @param points the observed rate, by time
	 * @param start the range's start, in seconds since the epoch
	 * @param end the range's end
	 * @param workload the workload, for its duration
	 * @return the pattern, {@code {kind: trace, points, source}}, its source the note
	 */
	static ObjectNode trace(List<Point> points, double start, double end, ObjectNode workload) {
		double span = end - start;
		JsonNode durationS = workload.path("durationS");
		double duration = PythonText.truthy(durationS) ? durationS.doubleValue() : DEFAULT_DURATION_S;
		double scale = duration / span;
		String note = note(span, duration, scale);
		List<Point> raw = new ArrayList<>();
		for (Point point : points) {
			if (start <= point.time() && point.time() <= end) {
				raw.add(new Point((point.time() - start) * scale, Math.min(Math.max(point.value(), 0.0), MAX_RATE)));
			}
		}
		ObjectNode pattern = workload.objectNode().put("kind", "trace");
		pattern.set("points", tracePoints(averaged(raw), duration));
		return pattern.put("source", note);
	}

	private static String note(double span, double duration, double scale) {
		String seen = "observed rate over " + spanText(span);
		String run = PythonFormat.format(duration, "g") + " s run";
		if (Math.abs(scale - 1.0) <= SAME_SCALE * Math.max(Math.abs(scale), 1.0)) {
			return seen + " replayed in real time in the " + run;
		}
		if (scale < 1) {
			return seen + " compressed into the " + run + " (" + PythonFormat.format(1 / scale, ",.1f") + "x faster)";
		}
		return seen + " stretched over the " + run + " (" + PythonFormat.format(scale, ",.1f") + "x slower)";
	}

	/**
	 * Points in groups, each the first's time and the group's mean rate, when there are
	 * more than {@link #MAX_TRACE_POINTS}.
	 * @param raw the observed rate, by time
	 * @return the points, averaged in groups when there are too many
	 */
	private static List<Point> averaged(List<Point> raw) {
		if (raw.size() <= MAX_TRACE_POINTS) {
			return raw;
		}
		int size = (int) Math.ceil((double) raw.size() / MAX_TRACE_POINTS);
		List<Point> averaged = new ArrayList<>();
		for (int i = 0; i < raw.size(); i += size) {
			List<Point> group = raw.subList(i, Math.min(i + size, raw.size()));
			averaged.add(new Point(group.getFirst().time(),
					FloatSum.of(group.stream().mapToDouble(Point::value)) / group.size()));
		}
		return averaged;
	}

	/**
	 * The pattern's points: rising times, from 0, at least two.
	 * @param raw the observed rate, by run time
	 * @param duration the run's duration, for a single point's end
	 * @return the points as {@code [time, rate]} pairs
	 */
	private static ArrayNode tracePoints(List<Point> raw, double duration) {
		List<double[]> points = new ArrayList<>();
		for (Point point : raw) {
			if (points.isEmpty() || point.time() > points.getLast()[0]) {
				points.add(new double[] { PythonRound.round(point.time(), RATE_DIGITS),
						PythonRound.round(point.value(), RATE_DIGITS) });
			}
		}
		if (points.isEmpty() || points.getFirst()[0] != 0.0) {
			points.addFirst(new double[] { 0.0, points.isEmpty() ? 0.0 : points.getFirst()[1] });
		}
		if (points.size() < 2) {
			points.add(new double[] { PythonRound.round(duration, RATE_DIGITS), points.getFirst()[1] });
		}
		ArrayNode array = JsonNodeFactory.instance.arrayNode();
		points.forEach((point) -> array.addArray().add(point[0]).add(point[1]));
		return array;
	}

	/**
	 * A span of time in words.
	 * @param seconds the span
	 * @return hours from two hours, minutes from two minutes, else seconds
	 */
	static String spanText(double seconds) {
		if (seconds >= HOURS_FROM) {
			return PythonFormat.format(seconds / SECONDS_PER_HOUR, ".1f") + " h";
		}
		if (seconds >= MINUTES_FROM) {
			return PythonFormat.format(seconds / SECONDS_PER_MINUTE, ".0f") + " min";
		}
		return PythonFormat.format(seconds, ".0f") + " s";
	}

}
