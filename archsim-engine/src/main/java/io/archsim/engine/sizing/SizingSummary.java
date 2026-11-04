package io.archsim.engine.sizing;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A design's sizing summary. Written by {@code RecordCodec}.
 *
 * @param auto whether the design auto-sizes its parts
 * @param targetCpuUtilization the CPU utilization auto-sizing aims for at the peak
 * @param nodes each sized part's summary, by its id, in the design's order
 */
public record SizingSummary(boolean auto, double targetCpuUtilization, Map<String, PartSummary> nodes) {

	/**
	 * Copies the summaries, so the record does not change.
	 */
	public SizingSummary {
		nodes = Collections.unmodifiableMap(new LinkedHashMap<>(nodes));
	}

}
