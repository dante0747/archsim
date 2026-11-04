package io.archsim.engine.estimate;

import org.jspecify.annotations.Nullable;

import io.archsim.model.codec.OmitWhenDefault;

/**
 * A part the formulas cannot estimate, and why.
 *
 * @param id its id
 * @param label its label
 * @param type its component type
 * @param onPath whether it is on a client's request path
 * @param arrivalRps the requests reaching it per second; {@code null} when that depends
 * on the run
 * @param status always {@link PartStatus#UNKNOWN}
 * @param note why there is no estimate
 * @param perRequestMs a queue's publish time per client request, which the Python engine
 * also gives a queue whose consumers it cannot estimate; left out otherwise
 */
public record UnestimatedPart(String id, String label, String type, boolean onPath, @Nullable Double arrivalRps,
		PartStatus status, String note, @OmitWhenDefault @Nullable Double perRequestMs) implements PartEstimate {

	/**
	 * This estimate with the part's time per client request.
	 * @param ms its time per client request, rounded
	 * @return the estimate with it
	 */
	UnestimatedPart withPerRequestMs(@Nullable Double ms) {
		return new UnestimatedPart(this.id, this.label, this.type, this.onPath, this.arrivalRps, this.status, this.note,
				ms);
	}

}
