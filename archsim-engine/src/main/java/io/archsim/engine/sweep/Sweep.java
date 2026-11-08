package io.archsim.engine.sweep;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.document.Objectives;

/**
 * A breaking-point sweep, run: its curve read against the design's objectives, the range
 * it covered and the loads it ran.
 *
 * @param analysis the curve read: the knee, the heaviest load within the objectives, the
 * verdict
 * @param objectives the design's objectives, with their defaults
 * @param range the range swept, within what a step can run
 * @param levels the loads run, from the lightest
 */
public record Sweep(SweepAnalysis analysis, Objectives objectives, SweepRange range, List<Double> levels) {

	/**
	 * Copies the loads, so the record does not change.
	 */
	public Sweep {
		levels = List.copyOf(levels);
	}

	/**
	 * The heaviest load that met the objectives, with every lighter one.
	 * @return requests per second; none when the lightest missed them
	 */
	public Optional<Double> maxWithinSloRps() {
		return Optional.ofNullable(this.analysis.maxWithinSlo()).map(SweepRow::rps);
	}

	/**
	 * The sweep as the Python engine's {@code breaking_point} wrote it: the analysis'
	 * fields, then the objectives, the range and the loads.
	 * @return its JSON
	 */
	public ObjectNode toJson() {
		ObjectNode json = RecordCodec.write(this.analysis);
		json.set("objectives", RecordCodec.write(this.objectives));
		json.putArray("range").add(this.range.fromRps()).add(this.range.toRps());
		ArrayNode loads = json.putArray("levels");
		this.levels.forEach(loads::add);
		return json;
	}

}
