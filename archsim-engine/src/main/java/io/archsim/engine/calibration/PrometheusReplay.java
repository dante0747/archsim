package io.archsim.engine.calibration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.DoublePredicate;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.fit.Histogram;
import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.RateSource;
import io.archsim.engine.calibration.mapping.Reduction;
import io.archsim.engine.calibration.mapping.Replay;
import io.archsim.engine.calibration.mapping.ReplayTarget;
import io.archsim.engine.calibration.mapping.WorkloadInput;
import io.archsim.engine.calibration.prometheus.PrometheusSeries;
import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;
import io.archsim.engine.numeric.FloatSum;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.engine.simulation.SimulationLimits;
import io.archsim.model.text.PythonText;

/**
 * The calibrated design simulated and compared with what was observed, as the Python
 * engine's {@code replay_check} compares them: latency and throughput, end to end and per
 * part, and the CPU and memory of each part whose CPU or working set was fitted (the mean
 * CPU cores over the run's measured window, the peak working set per replica against the
 * simulated memory peak).
 */
final class PrometheusReplay {

	private static final List<String> QUANTILES = List.of("p50", "p95", "p99");

	private static final List<Double> SHARES = List.of(0.5, 0.95, 0.99);

	private static final double MIB_PER_GIB = 1024.0;

	private static final int DIGITS = 4;

	private static final double DEFAULT_DURATION_S = 60;

	private final PrometheusRun run;

	private final ReplaySimulator simulator;

	private final @Nullable SimulationLimits limits;

	PrometheusReplay(PrometheusRun run, ReplaySimulator simulator, @Nullable SimulationLimits limits) {
		this.run = run;
		this.simulator = simulator;
		this.limits = limits;
	}

	/**
	 * Which observed times the replay's measured window covers.
	 * @param mapping the mapping
	 * @param workload the calibrated workload
	 * @param start the range's start
	 * @param end the range's end
	 * @return the times it covers; empty when the workload was not calibrated from the
	 * range, so the replay does not run what was observed
	 */
	static Optional<DoublePredicate> replayed(CalibrationMapping mapping, JsonNode workload, double start, double end) {
		RateSource source = mapping.workload().get(WorkloadInput.RPS);
		if (source == null) {
			return Optional.empty();
		}
		if (!source.trace() || end <= start) {
			return Optional.of((time) -> true);
		}
		JsonNode duration = workload.path("durationS");
		JsonNode warmup = workload.path("warmupS");
		double scale = (PythonText.truthy(duration) ? duration.doubleValue() : DEFAULT_DURATION_S) / (end - start);
		double warmupS = PythonText.truthy(warmup) ? warmup.doubleValue() : 0;
		return Optional.of((time) -> (time - start) * scale >= warmupS);
	}

	/**
	 * Simulates the calibrated design and compares it with what was observed.
	 * @param replay the latency and throughput to compare with; {@code null} for none
	 * @param observed what each fitted part used; empty for nothing
	 * @param keep which observed times count; {@code null} for all
	 * @return the checks, and the run's result digest
	 */
	Result check(@Nullable Replay replay, Map<String, Observed> observed, @Nullable DoublePredicate keep) {
		JsonNode report = this.simulator.simulate(this.run.run().document(), this.limits);
		ArrayNode checks = JsonNodeFactory.instance.arrayNode();
		Map<String, JsonNode> nodes = new HashMap<>();
		report.path("nodes").forEach((node) -> nodes.put(node.path("id").asString(), node));
		if (replay != null && replay.endToEnd() != null) {
			JsonNode summary = report.path("summary");
			compare(replay.endToEnd(), new Subject("end_to_end", null, "End to end", summary.path("latencyMs"),
					summary.path("throughputRps")), checks);
		}
		Map<String, ReplayTarget> components = (replay != null) ? replay.components() : Map.of();
		components.forEach((nodeId, target) -> {
			JsonNode node = nodes.get(nodeId);
			if (node != null) {
				compare(target, subject(node), checks);
			}
		});
		List<Boolean> measured = measured(report);
		observed.forEach((nodeId, seen) -> {
			JsonNode node = nodes.get(nodeId);
			if (node != null) {
				resources(node, seen, new Window(measured, keep), checks);
			}
		});
		return new Result(checks, report.path("provenance").path("resultDigest").asString());
	}

	/**
	 * One check, as the Python engine's {@code _check} writes it.
	 * @param subject what is checked
	 * @param metric the metric, such as {@code p95}
	 * @param observed what was observed
	 * @param simulated what the simulation gave; {@code null} for nothing
	 * @return {@code {scope, id, label, metric, observed, simulated, error}}
	 */
	static ObjectNode check(Subject subject, String metric, double observed, @Nullable Double simulated) {
		Double error = (simulated != null && observed != 0) ? (simulated - observed) / observed : null;
		return JsonNodeFactory.instance.objectNode()
			.put("scope", subject.scope())
			.put("id", subject.id())
			.put("label", subject.label())
			.put("metric", metric)
			.put("observed", PythonRound.round(observed, DIGITS))
			.put("simulated", (simulated != null) ? PythonRound.round(simulated, DIGITS) : null)
			.put("error", (error != null) ? PythonRound.round(error, DIGITS) : null);
	}

	private void compare(ReplayTarget target, Subject subject, ArrayNode checks) {
		Histogram histogram = PrometheusSeries.histogram(this.run.query(target.histogram()), target.unit());
		for (int i = 0; i < QUANTILES.size(); i++) {
			OptionalDouble observed = histogram.quantile(SHARES.get(i));
			if (observed.isPresent()) {
				checks.add(check(subject, QUANTILES.get(i), observed.getAsDouble(),
						number(subject.latency().path(QUANTILES.get(i)))));
			}
		}
		String throughput = target.throughput();
		if (throughput != null && !throughput.isEmpty()) {
			double observedRps = PrometheusSeries.reduce(this.run.query(throughput), Reduction.MEAN);
			checks.add(check(subject, "throughputRps", observedRps, number(subject.throughput())));
		}
	}

	private static void resources(JsonNode node, Observed seen, Window window, ArrayNode checks) {
		Subject subject = subject(node);
		List<Double> cpu = kept(seen.cpu(), window.keep());
		if (!cpu.isEmpty()) {
			List<Double> busy = new ArrayList<>();
			JsonNode series = node.path("timeseries").path("cpuBusyCores");
			for (int i = 0; i < Math.min(series.size(), window.measured().size()); i++) {
				if (window.measured().get(i) && series.get(i).isNumber()) {
					busy.add(series.get(i).doubleValue());
				}
			}
			Double simulated = busy.isEmpty() ? null : mean(busy);
			checks.add(check(subject, "cpuCores", mean(cpu), simulated));
		}
		List<Double> memory = kept(seen.memory(), window.keep());
		if (!memory.isEmpty()) {
			JsonNode peak = node.path("summary").path("memoryPeakGiB");
			Double simulated = peak.isNumber() ? peak.doubleValue() * MIB_PER_GIB : null;
			checks.add(check(subject, "memoryMiB", memory.stream().mapToDouble(Double::doubleValue).max().orElse(0),
					simulated));
		}
	}

	/**
	 * Which of the run's time buckets its measured window covers.
	 * @param report the run's report
	 * @return for each bucket, whether its middle is after the warm-up and before the end
	 */
	private static List<Boolean> measured(JsonNode report) {
		JsonNode window = report.path("window");
		double bucket = report.path("timeseries").path("bucketS").doubleValue();
		List<Boolean> measured = new ArrayList<>();
		report.path("timeseries").path("t").forEach((time) -> {
			double middle = time.doubleValue() + bucket / 2;
			measured
				.add(window.path("warmupS").doubleValue() <= middle && middle < window.path("durationS").doubleValue());
		});
		return measured;
	}

	private static Subject subject(JsonNode node) {
		JsonNode summary = node.path("summary");
		JsonNode latency = summary.path("latencyMs");
		return new Subject("component", node.path("id").asString(), node.path("label").asString(),
				PythonText.truthy(latency) ? latency : JsonNodeFactory.instance.objectNode(),
				summary.path("throughputRps"));
	}

	private static List<Double> kept(List<Point> points, @Nullable DoublePredicate keep) {
		return points.stream().filter((point) -> keep == null || keep.test(point.time())).map(Point::value).toList();
	}

	private static double mean(List<Double> values) {
		return FloatSum.of(values.stream().mapToDouble(Double::doubleValue)) / values.size();
	}

	private static @Nullable Double number(JsonNode value) {
		return value.isNumber() ? value.doubleValue() : null;
	}

	/**
	 * What a check is about.
	 *
	 * @param scope {@code end_to_end} or {@code component}
	 * @param id the part's id; {@code null} end to end
	 * @param label what it is called
	 * @param latency its simulated latency quantiles
	 * @param throughput its simulated throughput
	 */
	record Subject(String scope, @Nullable String id, String label, JsonNode latency, JsonNode throughput) {

	}

	/**
	 * Which buckets the run measured, and which observed times count.
	 *
	 * @param measured for each bucket, whether it is measured
	 * @param keep which observed times count; {@code null} for all
	 */
	private record Window(List<Boolean> measured, @Nullable DoublePredicate keep) {

	}

	/**
	 * What a replay found.
	 *
	 * @param checks the checks
	 * @param digest the run's result digest
	 */
	record Result(ArrayNode checks, String digest) {

	}

}
