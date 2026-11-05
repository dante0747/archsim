package io.archsim.engine.report;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * One part of the design in a run's report.
 *
 * @param id the part's id
 * @param label its name
 * @param summary its figures over the measured window
 * @param timeseries its figures per time bucket, by name; which series a part has depends
 * on its kind, and a series has a gap ({@code null}) where a bucket measured nothing
 */
public record ReportNode(String id, String label, NodeSummary summary, Map<String, List<@Nullable Double>> timeseries) {

	/**
	 * Checks that the part has its id, name and figures, and copies its series; a part
	 * without series has none.
	 */
	public ReportNode {
		Objects.requireNonNull(id, "a report's part has its id");
		Objects.requireNonNull(label, "a report's part has its name");
		Objects.requireNonNull(summary, "a report's part has its figures");
		timeseries = ReportJson.seriesByName(timeseries);
	}

	/**
	 * Reads a part of a report.
	 * @param json the part
	 * @return what the engine reads of it
	 * @throws tools.jackson.core.JacksonException when the JSON is not a report's part
	 */
	public static ReportNode read(JsonNode json) {
		return ReportJson.read(json, ReportNode.class);
	}

	/**
	 * A time series of the part.
	 * @param name such as {@code queueLength}
	 * @return its values; none when the part has no such series
	 */
	public List<@Nullable Double> series(String name) {
		return this.timeseries.getOrDefault(name, List.of());
	}

}
