package io.archsim.engine.calibration;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.provenance.InputPaths;
import io.archsim.model.DesignDocuments;
import io.archsim.model.text.PythonText;

/**
 * One calibration as it runs: the design it calibrates (a validated copy of the input),
 * each input it sets with its provenance, and what it notices.
 */
final class CalibrationRun {

	private final ObjectNode document;

	private final Map<String, ObjectNode> nodes = new LinkedHashMap<>();

	private final Map<String, ObjectNode> edges = new LinkedHashMap<>();

	private final List<Change> changes = new ArrayList<>();

	private final List<String> notes = new ArrayList<>();

	private final Map<String, ObjectNode> provenance = new LinkedHashMap<>();

	private final Clock clock;

	private final String source;

	private final String timeRange;

	/**
	 * A calibration of a design.
	 * @param document the design as given, validated and brought to the current schema
	 * first
	 * @param clock when it runs
	 * @param source what its provenance names as the telemetry's source
	 * @param timeRange the range the telemetry covers, {@code START/END}
	 * @throws io.archsim.model.issue.DocumentInvalidException when the design is not
	 * valid
	 */
	CalibrationRun(JsonNode document, Clock clock, String source, String timeRange) {
		this.document = DesignDocuments.toJson(DesignDocuments.load(document));
		this.document.path("architecture")
			.path("nodes")
			.forEach((node) -> this.nodes.put(node.path("id").asString(), (ObjectNode) node));
		this.document.path("architecture")
			.path("edges")
			.forEach((edge) -> this.edges.put(edge.path("id").asString(), (ObjectNode) edge));
		this.clock = clock;
		this.source = source;
		this.timeRange = timeRange;
	}

	ObjectNode document() {
		return this.document;
	}

	@Nullable ObjectNode node(String id) {
		return this.nodes.get(id);
	}

	@Nullable ObjectNode edge(String id) {
		return this.edges.get(id);
	}

	List<String> notes() {
		return this.notes;
	}

	void note(String note) {
		this.notes.add(note);
	}

	String source() {
		return this.source;
	}

	String timeRange() {
		return this.timeRange;
	}

	String now() {
		return CalibrationTimes.now(this.clock);
	}

	int inputs() {
		return this.provenance.size();
	}

	/**
	 * A provenance record of a measured input.
	 * @param query what it was read from; {@code null} for nothing
	 * @param fitError the fit's error; {@code null} to leave it out
	 * @param note more about it; {@code null} or empty to leave it out
	 * @return {@code {kind, source, query, timeRange, fittedAt, fitError?, note?}}
	 */
	ObjectNode record(@Nullable String query, @Nullable Double fitError, @Nullable String note) {
		ObjectNode record = this.document.objectNode()
			.put("kind", "measured")
			.put("source", this.source)
			.put("query", query)
			.put("timeRange", this.timeRange)
			.put("fittedAt", now());
		if (fitError != null) {
			record.put("fitError", fitError);
		}
		if (note != null && !note.isEmpty()) {
			record.put("note", note);
		}
		return record;
	}

	/**
	 * A service time's provenance from traces, in the order the Python engine's
	 * {@code calibrate_otlp} writes it: its fit error before when it was fitted.
	 * @param query what was fitted, in words
	 * @param fitError the fit's error; {@code null} for none
	 * @param note how many spans
	 * @return {@code {kind, source, query, timeRange, fitError?, fittedAt, note}}
	 */
	ObjectNode spansRecord(String query, @Nullable Double fitError, String note) {
		ObjectNode record = this.document.objectNode()
			.put("kind", "measured")
			.put("source", this.source)
			.put("query", query)
			.put("timeRange", this.timeRange);
		if (fitError != null) {
			record.put("fitError", fitError);
		}
		return record.put("fittedAt", now()).put("note", note);
	}

	/**
	 * Records an input set: its provenance and its change.
	 * @param record its provenance
	 * @param change the change
	 */
	void measured(ObjectNode record, Change change) {
		this.provenance.put(change.path(), record);
		this.changes.add(change);
	}

	/**
	 * Stamps each provenance record with the value its input holds, so the input counts
	 * as measured only while it keeps that value, and adds the records to the design's.
	 * @throws io.archsim.model.issue.DocumentInvalidException when the calibrated design
	 * is not valid
	 */
	void stamp() {
		ObjectNode canonical = DesignDocuments.toJson(DesignDocuments.load(this.document));
		this.provenance.forEach((path, record) -> record.set("value", InputPaths.valueAt(canonical, path)));
		ObjectNode records = this.document.path("provenance").isObject() ? (ObjectNode) this.document.get("provenance")
				: this.document.putObject("provenance");
		this.provenance.forEach((path, record) -> records.set(path, record.deepCopy()));
		DesignDocuments.load(this.document);
	}

	/**
	 * The outcome when nothing was calibrated: the design as it was, validated and
	 * brought to the current schema, with the notes.
	 * @return the outcome
	 */
	CalibrationOutcome unchanged() {
		return new CalibrationOutcome(this.document, this.changes, this.notes);
	}

	/**
	 * The outcome, with the calibration's record in the design.
	 * @param calibration the record
	 * @return the outcome
	 * @throws io.archsim.model.issue.DocumentInvalidException when the calibrated design
	 * is not valid
	 */
	CalibrationOutcome complete(ObjectNode calibration) {
		this.document.set("calibration", calibration);
		DesignDocuments.load(this.document);
		return new CalibrationOutcome(this.document, this.changes, this.notes);
	}

	/**
	 * A part's or a link's config, as the design holds it.
	 * @param item the part or the link
	 * @return its config
	 */
	static ObjectNode config(ObjectNode item) {
		return item.withObjectProperty("config");
	}

	/**
	 * A part's name in a change's label.
	 * @param node the part
	 * @return its label, else its id
	 */
	static String label(ObjectNode node) {
		JsonNode label = node.path("label");
		return PythonText.truthy(label) ? PythonText.str(label) : node.path("id").asString();
	}

	/**
	 * The first characters of a text.
	 * @param text the text
	 * @param count how many characters, as Python counts them
	 * @return the text, cut to that many
	 */
	static String first(String text, int count) {
		return (text.codePointCount(0, text.length()) <= count) ? text
				: text.substring(0, text.offsetByCodePoints(0, count));
	}

}
