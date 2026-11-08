package io.archsim.engine.reporting;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.report.RunReport;

/**
 * A report being written, after its parts have their status. The Python engine's analyses
 * read the report before its findings and its provenance were written, both of which the
 * engine's report records require (ADR-0039). The provenance is final by then, its
 * digests covering only the figures, so a draft reads with it, and gets it last, where
 * Python wrote it; until the findings are set, it reads with none.
 */
final class DraftReport {

	private final ObjectNode report;

	private final ObjectNode provenance;

	DraftReport(ObjectNode report, ObjectNode provenance) {
		this.report = report;
		this.provenance = provenance;
	}

	/**
	 * Sets a part of the report; a part set again keeps its place.
	 * @param key the part's name
	 * @param value the part
	 */
	void set(String key, JsonNode value) {
		this.report.set(key, value);
	}

	/**
	 * The report so far, as the engine's analyses read a report.
	 * @return the report so far, with its provenance, and no findings before they are set
	 */
	RunReport read() {
		ObjectNode view = Figures.NODES.objectNode();
		view.setAll(this.report);
		if (!view.has("findings")) {
			view.putArray("findings");
		}
		view.set("provenance", this.provenance);
		return RunReport.read(view);
	}

	/**
	 * The finished report.
	 * @return the report, its provenance last
	 */
	ObjectNode finish() {
		this.report.set("provenance", this.provenance);
		return this.report;
	}

}
