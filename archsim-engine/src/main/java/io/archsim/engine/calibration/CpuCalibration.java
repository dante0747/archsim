package io.archsim.engine.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.PrometheusRun.FittedInput;
import io.archsim.engine.calibration.mapping.CpuPerRequestSource;
import io.archsim.engine.calibration.prometheus.PrometheusSeries;
import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;
import io.archsim.engine.calibration.resources.CpuFit;
import io.archsim.engine.calibration.resources.CpuFits;
import io.archsim.engine.calibration.resources.LoadCurve;
import io.archsim.engine.calibration.resources.RatePoint;
import io.archsim.model.catalog.Setting;
import io.archsim.model.text.PythonFormat;

/**
 * A part's {@code cpuShare} from CPU per request, and its {@code serviceTimeByLoad} when
 * CPU per request changed with load, as the Python engine's {@code _fit_cpu} sets them.
 */
final class CpuCalibration {

	/**
	 * The share a curve's factors must span to be set: less is within CPU counters'
	 * noise.
	 */
	static final double MIN_CURVE_CHANGE = 0.10;

	private static final String CPU_SHARE = "cpuShare";

	private static final String BY_LOAD = "serviceTimeByLoad";

	private CpuCalibration() {
	}

	/**
	 * Sets a part's CPU share, and its service time by load.
	 * @param run the calibration
	 * @param node the part
	 * @param source its CPU and request rate queries
	 * @return the CPU cores observed, by time, for the replay; none when nothing was set
	 */
	static List<Point> calibrate(PrometheusRun run, ObjectNode node, CpuPerRequestSource source) {
		String nodeId = node.path("id").asString();
		Map<String, Setting> known = run.settings(node);
		if (!known.containsKey(CPU_SHARE)) {
			run.run()
				.note(nodeId + ": CPU per request sets the CPU share of service time, which "
						+ run.type(node).displayName() + " does not have: skipped");
			return List.of();
		}
		Map<Double, Double> cpu = byTime(PrometheusSeries.summed(run.query(source.cpu())));
		Map<Double, Double> requests = byTime(PrometheusSeries.summed(run.query(source.requests())));
		List<Double> times = cpu.keySet().stream().filter(requests::containsKey).sorted().toList();
		ObjectNode config = PartConfigs.parsed(run.registry(), node);
		Optional<CpuFit> fit = CpuFits.fitShare(
				times.stream()
					.map((time) -> new RatePoint(requests.getOrDefault(time, 0.0), cpu.getOrDefault(time, 0.0)))
					.toList(),
				PartConfigs.meanServiceMs(run.registry(), run.run().document(), node), PartConfigs.replicas(config));
		if (fit.isEmpty()) {
			run.run()
				.note(nodeId + ": no requests (or no service time) in the range, so no CPU per request was measured; "
						+ "the CPU share is unchanged");
			return List.of();
		}
		Fitted fitted = new Fitted(run, node, "(" + source.cpu() + ") / (" + source.requests() + ")");
		set(fitted, known.get(CPU_SHARE), fit.get());
		loadCurve(fitted, fit.get(), config);
		return times.stream().map((time) -> new Point(time, cpu.getOrDefault(time, 0.0))).toList();
	}

	private static void set(Fitted fitted, Setting setting, CpuFit fit) {
		List<String> words = new ArrayList<>();
		words.add(PythonFormat.format(fit.cpuMs(), ".4g") + " ms of CPU per request (CPU seconds ÷ requests over "
				+ fit.points() + " points) ÷ " + PythonFormat.format(fit.serviceMs(), ".4g")
				+ " ms mean service time; fit error at and above the median rate.");
		words.addAll(fit.notes());
		fitted.run()
			.set(fitted.node(),
					new FittedInput(CPU_SHARE, setting.label(), JsonNodeFactory.instance.numberNode(fit.share()),
							fitted.query(), fit.error(), String.join(" ", words)));
		fit.notes().forEach((note) -> fitted.note(".cpuShare: " + note));
	}

	/**
	 * Sets {@code serviceTimeByLoad} from how CPU per request changed with the rate per
	 * replica, when it changed by more than {@link #MIN_CURVE_CHANGE}; a note when the
	 * part has no such setting, or its replicas change with load.
	 * @param fitted the part and the query
	 * @param fit the CPU fit, with its curve
	 * @param config the part's parsed configuration
	 */
	private static void loadCurve(Fitted fitted, CpuFit fit, ObjectNode config) {
		LoadCurve curve = fit.curve();
		if (curve == null || curve.span() <= MIN_CURVE_CHANGE) {
			return;
		}
		Setting setting = fitted.run().settings(fitted.node()).get(BY_LOAD);
		if (setting == null) {
			fitted.note(": " + curve.text() + "; it keeps one CPU cost per request, weighted to the busiest times, so "
					+ "it under-counts CPU at quiet times");
			return;
		}
		if (PartConfigs.scales(config)) {
			fitted.note(": " + curve.text() + ", but its replicas change with load, so the rate per replica is "
					+ "unknown: service time by load not set");
			return;
		}
		String note = curve.text() + "; factors relative to " + PythonFormat.format(curve.referenceMs(), ".4g")
				+ " ms (CPU per request over the range), interpolated in log(req/s) and flat outside the bins; fit "
				+ "error = how far the never-rising fit is from a bin.";
		fitted.run()
			.set(fitted.node(),
					new FittedInput(BY_LOAD, setting.label(), curve.table(), fitted.query(), curve.error(), note));
		fitted.note(".serviceTimeByLoad: " + curve.text());
	}

	/**
	 * A series' points by their time.
	 * @param points the points
	 * @return each value by its time
	 */
	static Map<Double, Double> byTime(List<Point> points) {
		Map<Double, Double> byTime = new TreeMap<>();
		points.forEach((point) -> byTime.put(point.time(), point.value()));
		return byTime;
	}

	/**
	 * The part a CPU fit is for.
	 *
	 * @param run the calibration
	 * @param node the part
	 * @param query the CPU per request query
	 */
	private record Fitted(PrometheusRun run, ObjectNode node, String query) {

		/**
		 * Notes what was noticed about the part.
		 * @param text what follows the part's id
		 */
		void note(String text) {
			this.run.run().note(this.node.path("id").asString() + text);
		}

	}

}
