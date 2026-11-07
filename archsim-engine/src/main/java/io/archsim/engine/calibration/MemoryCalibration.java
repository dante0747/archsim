package io.archsim.engine.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.ToDoubleFunction;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.PrometheusRun.FittedInput;
import io.archsim.engine.calibration.mapping.MemoryUnit;
import io.archsim.engine.calibration.mapping.WorkingSetSource;
import io.archsim.engine.calibration.prometheus.PrometheusSeries;
import io.archsim.engine.calibration.prometheus.PrometheusSeries.Point;
import io.archsim.engine.calibration.resources.MemoryFit;
import io.archsim.engine.calibration.resources.MemoryFits;
import io.archsim.model.catalog.Setting;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * A part's memory at idle, per request per second and per request held, from its working
 * set, as the Python engine's {@code _fit_working_set} sets them.
 */
final class MemoryCalibration {

	private static final double MAX_BASELINE_MB = 1_048_576.0;

	private static final double MAX_PER_UNIT_MB = 65_536.0;

	/** The settings a working-set fit sets, in the Python engine's order. */
	private static final List<Field> FIELDS = List.of(
			new Field("memoryBaselineMb", MAX_BASELINE_MB, MemoryFit::baselineMb),
			new Field("memoryPerRpsMb", MAX_PER_UNIT_MB, MemoryFit::perRpsMb),
			new Field("memoryPerRequestMb", MAX_PER_UNIT_MB, MemoryFit::perRequestMb));

	private MemoryCalibration() {
	}

	/**
	 * Sets a part's memory settings.
	 * @param run the calibration
	 * @param node the part
	 * @param source its working set and what it is fitted against
	 * @return the working set per replica observed, in MiB by time, for the replay; none
	 * when nothing was set
	 */
	static List<Point> calibrate(PrometheusRun run, ObjectNode node, WorkingSetSource source) {
		String nodeId = node.path("id").asString();
		Map<String, Setting> known = run.settings(node);
		if (!FIELDS.stream().allMatch((field) -> known.containsKey(field.key()))) {
			run.run()
				.note(nodeId + ": " + run.type(node).displayName() + " has no memory at idle, per req/s and per "
						+ "request held to fit: skipped");
			return List.of();
		}
		ObjectNode config = PartConfigs.parsed(run.registry(), node);
		if (PartConfigs.serverless(config)) {
			run.run()
				.note(nodeId + ": a function's memory is per instance (one request each), not a working set that "
						+ "follows load: memory unchanged");
			return List.of();
		}
		List<PerReplica> points = perReplica(run, nodeId, source, config);
		Optional<MemoryFit> fit = points.isEmpty() ? Optional.empty() : fit(points);
		if (fit.isEmpty()) {
			run.run().note(nodeId + ": no working set in the range: memory unchanged");
			return List.of();
		}
		set(run, node, fit.get(), source.workingSet());
		return points.stream().map((point) -> new Point(point.time(), point.workingSet())).toList();
	}

	/**
	 * The working set fitted against what the mapping gives, which every point has.
	 * @param points the points per replica, at least one
	 * @return the fit; empty when there is nothing to fit
	 */
	private static Optional<MemoryFit> fit(List<PerReplica> points) {
		PerReplica first = points.getFirst();
		List<Double> rates = (first.rate() != null)
				? points.stream().map((point) -> Objects.requireNonNull(point.rate())).toList() : null;
		List<Double> held = (first.held() != null)
				? points.stream().map((point) -> Objects.requireNonNull(point.held())).toList() : null;
		return MemoryFits.fit(points.stream().map(PerReplica::workingSet).toList(), rates, held);
	}

	/**
	 * The working set, request rate and requests held per replica, at the times every
	 * query has a point and there are replicas.
	 * @param run the calibration
	 * @param nodeId the part's id
	 * @param source its queries
	 * @param config its parsed config
	 * @return the points, by time
	 */
	private static List<PerReplica> perReplica(PrometheusRun run, String nodeId, WorkingSetSource source,
			ObjectNode config) {
		Map<Double, Double> workingSet = CpuCalibration.byTime(PrometheusSeries.summed(run.query(source.workingSet())));
		Map<Double, Double> rate = series(run, source.requests());
		Map<Double, Double> held = series(run, source.inflight());
		Map<Double, Double> replicas = series(run, source.replicas());
		double fixed = PartConfigs.replicas(config);
		if (replicas == null && PythonText.truthy(config.path("autoscaling"))) {
			run.run()
				.note(nodeId + ": autoscaling, and the mapping gives no replicas query: the working set is taken as "
						+ "spread over " + PythonFormat.format(fixed, "g") + " replicas");
		}
		double toMib = source.unit().bytes() / MemoryUnit.MIB.bytes();
		List<PerReplica> points = new ArrayList<>();
		for (double time : common(workingSet, rate, held, replicas)) {
			double count = (replicas != null) ? replicas.getOrDefault(time, 0.0) : fixed;
			if (count > 0) {
				points.add(new PerReplica(time, workingSet.getOrDefault(time, 0.0) * toMib / count,
						share(rate, time, count), share(held, time, count)));
			}
		}
		return points;
	}

	private static @Nullable Double share(@Nullable Map<Double, Double> series, double time, double replicas) {
		return (series != null) ? series.getOrDefault(time, 0.0) / replicas : null;
	}

	/**
	 * The times every given series has a point.
	 * @param first the series every point must be in
	 * @param others the other series; {@code null} for one not given
	 * @return the times, rising
	 */
	@SafeVarargs
	private static Set<Double> common(Map<Double, Double> first, @Nullable Map<Double, Double>... others) {
		TreeSet<Double> times = new TreeSet<>(first.keySet());
		for (Map<Double, Double> other : others) {
			if (other != null) {
				times.retainAll(other.keySet());
			}
		}
		return times;
	}

	/**
	 * A series the mapping may give; as in the Python engine, an empty query is none.
	 * @param run the calibration
	 * @param query the query; {@code null} for none
	 * @return its values by time; {@code null} when not given
	 */
	private static @Nullable Map<Double, Double> series(PrometheusRun run, @Nullable String query) {
		if (query == null || query.isEmpty()) {
			return null;
		}
		return CpuCalibration.byTime(PrometheusSeries.summed(run.query(query)));
	}

	private static void set(PrometheusRun run, ObjectNode node, MemoryFit fit, String query) {
		Map<String, Setting> known = run.settings(node);
		String against = String.join(" and ",
				fit.against()
					.stream()
					.map((term) -> "rate".equals(term) ? "the request rate" : "requests held")
					.toList());
		List<String> words = new ArrayList<>();
		words.add("working set per replica = " + PythonFormat.format(fit.baselineMb(), ".4g") + " MiB + "
				+ PythonFormat.format(fit.perRpsMb(), ".4g") + " MiB per req/s + "
				+ PythonFormat.format(fit.perRequestMb(), ".4g") + " MiB per request held, fitted against " + against
				+ " over " + fit.points() + " points (non-negative least squares; a term not fitted against is zero, "
				+ "memory per request held its 0.01 MiB floor); fit error at and above the median.");
		words.addAll(fit.notes());
		for (Field field : FIELDS) {
			double value = Math.min(field.max(), field.value().applyAsDouble(fit));
			run.set(node, new FittedInput(field.key(), Objects.requireNonNull(known.get(field.key())).label(),
					JsonNodeFactory.instance.numberNode(value), query, fit.error(), String.join(" ", words)));
		}
		fit.notes().forEach((note) -> run.run().note(node.path("id").asString() + " memory: " + note));
	}

	/**
	 * A setting a working-set fit sets.
	 *
	 * @param key the setting
	 * @param max the most it takes, in MiB
	 * @param value its value in the fit
	 */
	private record Field(String key, double max, ToDoubleFunction<MemoryFit> value) {
	}

	/**
	 * A point per replica the fit takes.
	 *
	 * @param time its time
	 * @param workingSet the working set per replica, in MiB
	 * @param rate the request rate per replica; {@code null} when not fitted against
	 * @param held the requests held per replica; {@code null} when not fitted against
	 */
	private record PerReplica(double time, double workingSet, @Nullable Double rate, @Nullable Double held) {
	}

}
