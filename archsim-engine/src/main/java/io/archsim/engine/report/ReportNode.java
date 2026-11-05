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
 * @param type its type, such as {@code service}
 * @param category its type's category, such as {@code traffic} for a client group
 * @param label its name
 * @param summary its figures over the measured window
 * @param timeseries its figures per time bucket, by name; which series a part has depends
 * on its kind, and a series has a gap ({@code null}) where a bucket measured nothing
 * @param behaviour the behaviours its technology adds, such as {@code primary_reads}
 */
public record ReportNode(String id, String type, String category, String label, NodeSummary summary,
		Map<String, List<@Nullable Double>> timeseries, List<Behaviour> behaviour) {

	/**
	 * Checks that the part has its id, type, category, name and figures, and copies its
	 * series and behaviours; a part without them has none.
	 */
	public ReportNode {
		Objects.requireNonNull(id, "a report's part has its id");
		Objects.requireNonNull(type, "a report's part has its type");
		Objects.requireNonNull(category, "a report's part has its category");
		Objects.requireNonNull(label, "a report's part has its name");
		Objects.requireNonNull(summary, "a report's part has its figures");
		timeseries = ReportJson.seriesByName(timeseries);
		behaviour = (behaviour != null) ? List.copyOf(behaviour) : List.of();
	}

	/**
	 * Whether the part's technology adds a behaviour.
	 * @param id the behaviour's id, such as {@code primary_reads}
	 * @return whether it does
	 */
	public boolean behaves(String id) {
		return this.behaviour.stream().anyMatch((added) -> id.equals(added.id()));
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

	/**
	 * A behaviour a part's technology adds.
	 *
	 * @param id what it is, such as {@code primary_reads}
	 */
	public record Behaviour(@Nullable String id) {

	}

}
