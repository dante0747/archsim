package io.archsim.engine.calibration.resources;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * A part's working set per replica fitted as memory at idle plus memory per request per
 * second and per request held.
 *
 * @param baselineMb the memory at idle, in MiB
 * @param perRpsMb the MiB per request per second; 0 when the rate was not fitted against
 * @param perRequestMb the MiB per request held; {@link MemoryFits#MIN_PER_REQUEST_MB}
 * when requests held were not fitted against or added no memory
 * @param error the largest relative error at and above the median rate (or requests
 * held); {@code null} when none was measured there
 * @param points how many points the fit rests on
 * @param against what the working set was fitted against: {@code rate}, {@code held} or
 * both
 * @param notes what the fit could not tell, in words
 */
public record MemoryFit(double baselineMb, double perRpsMb, double perRequestMb, @Nullable Double error, int points,
		List<String> against, List<String> notes) {

	/**
	 * Copies what it was fitted against and the notes.
	 */
	public MemoryFit {
		against = List.copyOf(against);
		notes = List.copyOf(notes);
	}

}
