package io.archsim.engine.calibration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.PrometheusRun.FittedInput;
import io.archsim.engine.calibration.fit.Fit;
import io.archsim.engine.calibration.fit.LatencyFits;
import io.archsim.engine.calibration.mapping.CalibrationMapping;
import io.archsim.engine.calibration.mapping.CpuPerRequestSource;
import io.archsim.engine.calibration.mapping.DerivedInput;
import io.archsim.engine.calibration.mapping.GaugeSource;
import io.archsim.engine.calibration.mapping.HistogramSource;
import io.archsim.engine.calibration.mapping.MemoryUnit;
import io.archsim.engine.calibration.mapping.NodeSource;
import io.archsim.engine.calibration.mapping.RatioSource;
import io.archsim.engine.calibration.mapping.Reduction;
import io.archsim.engine.calibration.mapping.WorkingSetSource;
import io.archsim.engine.calibration.prometheus.PrometheusSeries;
import io.archsim.engine.numeric.PythonNumbers;
import io.archsim.engine.numeric.PythonRound;
import io.archsim.model.catalog.Setting;
import io.archsim.model.text.PythonFormat;

/**
 * The parts' settings a mapping reads from Prometheus, as the Python engine's
 * {@code calibrate_prometheus} sets them first: a histogram fitted to a distribution, a
 * gauge reduced to one figure, a hit ratio from hits and misses.
 */
final class SettingSources {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final int VALUE_DIGITS = 6;

	private SettingSources() {
	}

	/**
	 * Sets every part's mapped settings, noting parts and settings the design does not
	 * have.
	 * @param run the calibration
	 * @param mapping the mapping
	 */
	static void calibrate(PrometheusRun run, CalibrationMapping mapping) {
		mapping.nodes().forEach((nodeId, sources) -> {
			ObjectNode node = run.run().node(nodeId);
			if (node == null) {
				run.run().note("mapping names part '" + nodeId + "', which is not in the design: skipped");
				return;
			}
			sources.forEach((key, source) -> setting(run, node, key, source));
		});
	}

	/**
	 * Sets one of a part's settings from its source; derived inputs, such as CPU per
	 * request, are fitted later.
	 * @param run the calibration
	 * @param node the part
	 * @param key the setting's key
	 * @param source where it comes from
	 */
	private static void setting(PrometheusRun run, ObjectNode node, String key, NodeSource source) {
		if (DerivedInput.of(key).isPresent()) {
			return;
		}
		Setting setting = run.settings(node).get(key);
		if (setting == null) {
			run.run()
				.note(node.path("id").asString() + ": '" + key + "' is not a setting of " + run.type(node).displayName()
						+ ": skipped");
			return;
		}
		read(run, node, setting, source).ifPresent((input) -> run.set(node, input));
	}

	/**
	 * A setting's value from its source.
	 * @param run the calibration
	 * @param node the part
	 * @param setting the setting
	 * @param source where it comes from
	 * @return the input; empty when the source gives none, with a note
	 */
	private static Optional<FittedInput> read(PrometheusRun run, ObjectNode node, Setting setting, NodeSource source) {
		String nodeId = node.path("id").asString();
		return switch (source) {
			case HistogramSource histogram -> Optional.of(histogram(run, nodeId, setting, histogram));
			case GaugeSource gauge -> gauge(run, nodeId, setting, gauge);
			case RatioSource ratio -> ratio(run, nodeId, setting, ratio);
			case CpuPerRequestSource _, WorkingSetSource _ -> Optional.empty();
		};
	}

	private static FittedInput histogram(PrometheusRun run, String nodeId, Setting setting, HistogramSource source) {
		Fit fit = LatencyFits.fitHistogram(PrometheusSeries.histogram(run.query(source.histogram()), source.unit()),
				source.fit());
		fit.notes().forEach((note) -> run.run().note(nodeId + "." + setting.key() + ": " + note));
		List<String> words = new ArrayList<>();
		words.add(PythonFormat.format(fit.count(), ",.0f") + " observations per range step on average.");
		words.addAll(fit.notes());
		return new FittedInput(setting.key(), setting.label(), fit.distribution(), source.histogram(), fit.error(),
				String.join(" ", words));
	}

	private static Optional<FittedInput> gauge(PrometheusRun run, String nodeId, Setting setting, GaugeSource source) {
		double value = PrometheusSeries.reduce(run.query(source.gauge()), source.reduce());
		if (source.unit() != null) {
			Optional<MemoryUnit> unit = Optional.ofNullable(setting.unit()).flatMap(MemoryUnit::named);
			if (unit.isEmpty()) {
				run.run()
					.note(nodeId + ": a unit (" + source.unit().json() + ") is only for memory and size settings; '"
							+ setting.key() + "' is not one: skipped");
				return Optional.empty();
			}
			value = value * source.unit().bytes() / unit.get().bytes();
		}
		JsonNode rounded = (source.round() || "integer".equals(setting.kind()))
				? NODES.numberNode(PythonNumbers.round(value))
				: NODES.numberNode(PythonRound.round(value, VALUE_DIGITS));
		return Optional.of(new FittedInput(setting.key(), setting.label(), rounded, source.gauge(), null, ""));
	}

	private static Optional<FittedInput> ratio(PrometheusRun run, String nodeId, Setting setting, RatioSource source) {
		String hits = query(source, "hits");
		String misses = query(source, "misses");
		double hitRate = PrometheusSeries.reduce(run.query(hits), Reduction.MEAN);
		double missRate = PrometheusSeries.reduce(run.query(misses), Reduction.MEAN);
		if (!(hitRate + missRate > 0)) {
			run.run()
				.note(nodeId + ": no hits or misses in the range, so no hit ratio was measured; " + setting.key()
						+ " unchanged");
			return Optional.empty();
		}
		double ratio = PythonRound.round(hitRate / (hitRate + missRate), VALUE_DIGITS);
		return Optional.of(new FittedInput(setting.key(), setting.label(), NODES.numberNode(ratio),
				hits + " / (" + hits + " + " + misses + ")", null, ""));
	}

	private static String query(RatioSource source, String which) {
		return source.ratio().getOrDefault(which, "");
	}

}
