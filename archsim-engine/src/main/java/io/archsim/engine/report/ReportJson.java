package io.archsim.engine.report;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a run's report, or a part of one, into the engine's records of it (ADR-0039):
 * keys the records do not name are skipped, and a value the JSON leaves out or writes as
 * {@code null} reads as {@code null}, as the Python engine's {@code get} reads it. Unlike
 * the model's codec, nothing is validated beyond the parts every report has: a report is
 * the engine's own output. Only the report's records use it, each through its own
 * {@code read}.
 */
final class ReportJson {

	private static final JsonMapper JSON = JsonMapper.builder()
		.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
		.build();

	private ReportJson() {
	}

	/**
	 * Reads JSON into a record.
	 * @param <T> the record's type
	 * @param json the report, or the part of it the record holds
	 * @param type the record's type
	 * @return the record
	 * @throws tools.jackson.core.JacksonException when the JSON does not fit the record:
	 * a value of another kind, or a part a report always has missing
	 */
	static <T extends Record> T read(JsonNode json, Class<T> type) {
		return JSON.treeToValue(json, type);
	}

	/**
	 * A time series as the Python engine reads it ({@code series or []}): its values,
	 * gaps kept as {@code null}, and none when the report has no series.
	 * @param series the series, or {@code null}
	 * @return its values, unmodifiable
	 */
	static List<@Nullable Double> series(@Nullable List<@Nullable Double> series) {
		return (series != null) ? Collections.unmodifiableList(new ArrayList<>(series)) : List.of();
	}

	/**
	 * A part's time series by name, without those the report writes as {@code null},
	 * which the Python engine reads as none.
	 * @param byName the series by name as Jackson reads them, where a series the report
	 * writes as {@code null} is {@code null}; or {@code null}
	 * @return the series, unmodifiable, in the report's order
	 */
	static Map<String, List<@Nullable Double>> seriesByName(@Nullable Map<String, List<@Nullable Double>> byName) {
		if (byName == null) {
			return Map.of();
		}
		Map<String, List<@Nullable Double>> series = new LinkedHashMap<>();
		for (Map.Entry<String, List<@Nullable Double>> named : byName.entrySet()) {
			if (named.getValue() != null) {
				series.put(named.getKey(), series(named.getValue()));
			}
		}
		return Collections.unmodifiableMap(series);
	}

}
