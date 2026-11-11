package io.archsim.mcp;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

/**
 * The runs of a session an agent may ask for again by id, as the Python server kept them:
 * the last twenty, a run kept again moved to the newest.
 */
final class KeptRuns {

	/** The runs kept: the Python server's {@code MAX_RUNS_KEPT}. */
	static final int MAX_KEPT = 20;

	private final Map<String, JsonNode> reports = new LinkedHashMap<>();

	/**
	 * Keeps a run's report, the oldest dropped past the limit.
	 * @param id the run's id
	 * @param report its report
	 */
	synchronized void keep(String id, JsonNode report) {
		this.reports.remove(id);
		this.reports.put(id, report);
		while (this.reports.size() > MAX_KEPT) {
			this.reports.remove(this.reports.keySet().iterator().next());
		}
	}

	/**
	 * A kept run's report.
	 * @param id the run's id
	 * @return its report; empty when the run is not kept
	 */
	synchronized Optional<JsonNode> report(String id) {
		return Optional.ofNullable(this.reports.get(id));
	}

	/**
	 * The ids of the runs kept, the oldest first.
	 * @return the ids, joined as the Python server listed them; {@code none} for none
	 */
	synchronized String ids() {
		return this.reports.isEmpty() ? "none" : String.join(", ", this.reports.keySet());
	}

}
