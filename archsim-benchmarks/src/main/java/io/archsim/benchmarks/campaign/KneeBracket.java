package io.archsim.benchmarks.campaign;

import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.comparison.Knees;
import io.archsim.benchmarks.values.PythonValues;

/**
 * Where a run measures next to close in on its knee. The ladder's steps stand at shares
 * of the predicted knee, so a prediction far from the stack leaves the measured knee
 * between two steps far apart, where interpolating between them says little. The knee
 * lies between the highest load that met the objective and the first above it that missed
 * ({@link Knees#gridKnee(List)}, which the page reads the knee from too): the run
 * measures halfway between the two until they are within 3% of each other, and below a
 * ladder whose lowest step already missed.
 */
final class KneeBracket {

	/**
	 * How far apart the steps either side of the knee may stay, as a share of the higher.
	 */
	static final double RESOLUTION = 0.03;

	private static final String RATE = "offeredRps";

	private KneeBracket() {
	}

	/**
	 * The load to measure next.
	 * @param steps the steps measured so far, in any order
	 * @return halfway between the steps either side of the knee, or half the lowest step
	 * when it missed the objective. Empty when the two are within 3% of each other; when
	 * every step met the objective (the ladder climbs instead); when there is no whole
	 * load left between them, or half the lowest step is under the ladder's least step of
	 * 10 req/s; when no request succeeded at the lowest step (the stack is down, not
	 * slow); and when a step met the objective above one that missed it: a disturbed run,
	 * which measures no knee
	 */
	static Optional<Long> next(List<? extends JsonNode> steps) {
		if (!Knees.disturbedMeasuredSteps(steps).isEmpty()) {
			return Optional.empty();
		}
		JsonNode knee = Knees.gridKnee(steps);
		String bound = knee.get("bound").stringValue("");
		if ("interpolated".equals(bound)) {
			return between(knee.get("between").get(0).doubleValue(), knee.get("between").get(1).doubleValue());
		}
		return "below".equals(bound) ? below(steps, knee.get("belowRps")) : Optional.empty();
	}

	// halfway, rounded half to even as the ladder's steps are
	private static Optional<Long> between(double met, double missed) {
		long halfway = (long) Math.rint((met + missed) / 2);
		boolean apart = missed - met > RESOLUTION * missed;
		return (apart && halfway > met && halfway < missed) ? Optional.of(halfway) : Optional.empty();
	}

	private static Optional<Long> below(List<? extends JsonNode> steps, JsonNode lowest) {
		long half = (long) Math.rint(lowest.doubleValue() / 2);
		boolean answering = steps.stream()
			.filter((step) -> PythonValues.compare(step.get(RATE), lowest) == 0)
			.anyMatch((step) -> PythonValues.numberOrZero(step, "successRate") > 0);
		return (answering && half >= Prediction.LEAST_STEP) ? Optional.of(half) : Optional.empty();
	}

}
