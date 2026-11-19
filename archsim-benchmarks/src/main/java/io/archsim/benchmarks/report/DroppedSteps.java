package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;
import io.archsim.model.text.PythonText;

/**
 * The share of each step's requests k6 dropped in a run: it had no virtual user free to
 * send them, so the load was no longer open-loop there and what the step measured is a
 * lower bound. The page shows it for every run, since {@code measured.json} always kept
 * each repeat's dropped requests, and says how many virtual users k6 had: at least a
 * quarter of a second of arrivals' worth held ready from protocol v7 on; 50 ms' worth
 * before, and more as it went.
 */
final class DroppedSteps {

	private static final String DROPPED = "Dropped: the share of the step's requests k6 never sent, because it "
			+ "had no virtual user free (the median of the repeats). The load was then no longer open-loop: the "
			+ "step's latencies are a lower bound, and its success rate counts the unsent requests as failed. ";

	private static final String BEFORE_V7 = "k6 started this run's steps with virtual users for about 50 ms of "
			+ "arrivals and added more one at a time, up to 3,000, dropping requests meanwhile (every run before "
			+ "protocol v7). Past the knee the latencies it measured were held down by how fast it added them, and "
			+ "where the knee lies between the last step that met the objective and the first that missed it is "
			+ "not measured.";

	private static final String NONE = "-";

	private static final double PERCENT = 100;

	/** The least share shown as a figure, in percent. */
	private static final double LEAST_SHOWN = 0.1;

	/** The share from which no decimal is shown, in percent. */
	private static final double WHOLE_FROM = 10;

	private static final double TENTHS = 10;

	private final List<Step> steps;

	private final List<JsonNode> covers;

	private DroppedSteps(List<Step> steps, List<JsonNode> covers) {
		this.steps = steps;
		this.covers = covers;
	}

	/**
	 * The share of a run's requests k6 dropped, step by step.
	 * @param measured the run's {@code measured.json}, or None
	 * @return the steps' shares
	 */
	static DroppedSteps of(JsonNode measured) {
		List<Step> steps = new ArrayList<>();
		List<JsonNode> covers = new ArrayList<>();
		for (JsonNode step : measured.path("steps")) {
			steps.add(new Step(step.get("offeredRps"), medianShare(step)));
			JsonNode generator = PythonValues.get(step, "generator");
			if (PythonText.truthy(generator)) {
				covers.add(generator.get("coverS"));
			}
		}
		return new DroppedSteps(steps, covers);
	}

	/**
	 * The share of a step's requests k6 dropped, as the ladder shows it.
	 * @param rate the step's offered load
	 * @return the share in percent ({@code 2.8%}, {@code 46%}, {@code <0.1%}), or
	 * {@code -} when every request was sent
	 */
	String at(JsonNode rate) {
		double percent = this.steps.stream()
			.filter((step) -> PythonValues.compare(step.rate(), rate) == 0)
			.mapToDouble(Step::share)
			.findFirst()
			.orElse(0) * PERCENT;
		if (percent <= 0) {
			return NONE;
		}
		if (percent < LEAST_SHOWN) {
			return "<" + PythonFormat.format(LEAST_SHOWN, "g") + "%";
		}
		boolean oneDecimal = Math.rint(percent * TENTHS) / TENTHS < WHOLE_FROM;
		return PythonFormat.format(percent, oneDecimal ? ".1f" : ".0f") + "%";
	}

	/**
	 * What a step's numbers are worth where k6 dropped requests, and how many virtual
	 * users k6 had.
	 * @return the note; empty when no step dropped any
	 */
	Optional<String> note() {
		if (this.steps.stream().noneMatch((step) -> step.share() > 0)) {
			return Optional.empty();
		}
		if (this.covers.isEmpty()) {
			return Optional.of(DROPPED + BEFORE_V7);
		}
		return Optional.of(DROPPED + "k6 held virtual users ready for at least "
				+ Figures.format(PythonValues.min(this.covers), "g")
				+ " s of each step's arrivals (protocol v7), so it dropped requests only once that many were "
				+ "waiting for an answer.");
	}

	// the median over a step's repeats of the share each dropped; run 1's steps had no
	// repeats: the step is its one run
	private static double medianShare(JsonNode step) {
		JsonNode repeats = PythonValues.get(step, "runs");
		List<JsonNode> runs = PythonText.truthy(repeats) ? repeats.valueStream().toList() : List.of(step);
		return PythonValues.median(runs.stream().map(DroppedSteps::share).toList()).doubleValue();
	}

	private static JsonNode share(JsonNode repeat) {
		double dropped = PythonValues.numberOrZero(repeat, "dropped");
		double attempted = PythonValues.numberOrZero(repeat, "sent") + dropped;
		return PythonValues.number((attempted > 0) ? dropped / attempted : 0);
	}

	/**
	 * A step's share of requests dropped.
	 *
	 * @param rate its offered load
	 * @param share the share, the median of its repeats'
	 */
	private record Step(JsonNode rate, double share) {

	}

}
