package io.archsim.engine.calibration.mapping;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.Default;

/**
 * Which telemetry sets which input of a design: the calibration mapping file, its parts
 * and links addressed by their ids in the design.
 *
 * @param range the range of time read; {@code null} for the range the caller gives
 * @param nodes each part's settings' sources, by part id and then setting (or
 * {@link DerivedInput} key)
 * @param edges each link's latency source, by link id and then setting
 * @param workload the workload's inputs' sources
 * @param replay what the calibrated model is checked against; {@code null} for nothing
 */
public record CalibrationMapping(@Nullable TimeRange range, @Default("{}") Map<String, Map<String, NodeSource>> nodes,
		@Default("{}") Map<String, Map<String, LinkLatencySource>> edges,
		@Default("{}") Map<WorkloadInput, RateSource> workload, @Nullable Replay replay) {

	/**
	 * Copies the sources and checks that each derived input has the source it needs and
	 * that CPU and working-set sources are under their keys.
	 * @throws IllegalArgumentException with the Python engine's words otherwise
	 */
	public CalibrationMapping {
		nodes = nested(nodes);
		edges = nested(edges);
		workload = Collections.unmodifiableMap(new LinkedHashMap<>(workload));
		nodes.forEach((part, sources) -> sources.forEach((key, source) -> check(part, key, source)));
	}

	private static void check(String part, String key, NodeSource source) {
		String where = "nodes." + part + "." + key;
		Optional<DerivedInput> wanted = DerivedInput.of(key);
		if (wanted.isPresent() && !wanted.get().accepts(source)) {
			throw new IllegalArgumentException(where + " needs " + wanted.get().shape());
		}
		Optional<DerivedInput> derived = DerivedInput.ofSource(source);
		if (wanted.isEmpty() && derived.isPresent()) {
			throw new IllegalArgumentException(
					where + ": CPU and working-set sources go under '" + derived.get().key() + "'");
		}
	}

	private static <V> Map<String, Map<String, V>> nested(Map<String, Map<String, V>> map) {
		Map<String, Map<String, V>> copy = new LinkedHashMap<>();
		map.forEach((id, inner) -> copy.put(id, Collections.unmodifiableMap(new LinkedHashMap<>(inner))));
		return Collections.unmodifiableMap(copy);
	}

}
