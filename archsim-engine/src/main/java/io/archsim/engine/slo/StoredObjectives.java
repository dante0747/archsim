package io.archsim.engine.slo;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.model.document.Objectives;

/**
 * The objectives a run is judged by, read from the design it was stored with as the
 * Python engine read them ({@code objectives.get(...)}): a target the design leaves out
 * or sets to {@code null} is off. The model's codec would give a target left out its
 * default (300 ms, 0.999), which the Python engine never judged a run by.
 */
public final class StoredObjectives {

	/** No targets: every objective is off. */
	private static final Objectives NONE = new Objectives(null, null, null, null);

	private StoredObjectives() {
	}

	/**
	 * Reads stored objectives.
	 * @param objectives the design's {@code objectives}, or {@code null} when it has none
	 * @return the objectives; none when there is no object to read
	 */
	public static Objectives read(@Nullable JsonNode objectives) {
		if (objectives == null || !objectives.isObject()) {
			return NONE;
		}
		return new Objectives(StoredNumbers.number(objectives, "latencyP95Ms"),
				StoredNumbers.number(objectives, "latencyP99Ms"), StoredNumbers.number(objectives, "availability"),
				StoredNumbers.number(objectives, "minThroughputRps"));
	}

}
