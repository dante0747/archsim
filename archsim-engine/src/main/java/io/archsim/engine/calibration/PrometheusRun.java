package io.archsim.engine.calibration;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.calibration.mapping.TimeRange;
import io.archsim.engine.calibration.prometheus.PrometheusClient;
import io.archsim.engine.provenance.InputPaths;
import io.archsim.model.catalog.ComponentFacts;
import io.archsim.model.catalog.Setting;
import io.archsim.model.catalog.TypeFacts;
import io.archsim.model.component.ComponentRegistry;

/**
 * One calibration from Prometheus as it runs: the run, where its queries are answered and
 * over which range, and what the design's types are.
 *
 * @param run the run
 * @param client where the queries are answered
 * @param range the range read
 * @param start its start, in seconds since the epoch
 * @param end its end, in seconds since the epoch
 * @param registry the design's types
 * @param facts their settings and defaults
 */
record PrometheusRun(CalibrationRun run, PrometheusClient client, TimeRange range, double start, double end,
		ComponentRegistry registry, ComponentFacts facts) {

	/** The longest provenance note kept. */
	static final int NOTE_LENGTH = 500;

	/**
	 * A query's series over the range.
	 * @param query the query
	 * @return its series
	 */
	JsonNode query(String query) {
		return this.client.queryRange(query, this.start, this.end, this.range.step());
	}

	/**
	 * A part's type's settings and defaults.
	 * @param node the part
	 * @return its type's facts
	 */
	TypeFacts type(ObjectNode node) {
		String type = node.path("type").asString();
		return this.facts.type(type).orElseThrow(() -> new IllegalStateException("no catalog entry for " + type));
	}

	/**
	 * A part's type's settings by their names, as {@code describe_fields} lists them.
	 * @param node the part
	 * @return its settings
	 */
	Map<String, Setting> settings(ObjectNode node) {
		return type(node).settings().stream().collect(Collectors.toMap(Setting::key, Function.identity()));
	}

	/**
	 * Sets one of a part's inputs, with its provenance and its change.
	 * @param node the part
	 * @param input the input and how it was found
	 */
	void set(ObjectNode node, FittedInput input) {
		ObjectNode config = CalibrationRun.config(node);
		JsonNode before = config.has(input.key()) ? config.get(input.key()) : type(node).defaultValue(input.key());
		Change change = new Change(InputPaths.node(node.path("id").asString(), input.key()),
				CalibrationRun.label(node) + ": " + input.label(), before, input.value(), input.error(), input.query());
		config.set(input.key(), input.value().deepCopy());
		this.run.measured(
				this.run.record(input.query(), input.error(), CalibrationRun.first(input.note(), NOTE_LENGTH)), change);
	}

	/**
	 * An input a fit sets.
	 *
	 * @param key the setting's JSON name
	 * @param label the setting in words
	 * @param value its value
	 * @param query what it was read from
	 * @param error the fit's error; {@code null} for none
	 * @param note how it was fitted
	 */
	record FittedInput(String key, String label, JsonNode value, String query, @Nullable Double error, String note) {

	}

}
