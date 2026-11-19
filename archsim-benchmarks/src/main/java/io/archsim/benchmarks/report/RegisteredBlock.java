package io.archsim.benchmarks.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.HeldOut;

/**
 * The validation page's list of the held-out experiments that are registered and have no
 * kept run: what each runs and when it was registered. The page has no result to show for
 * them, and says so rather than leaving them out. It says only what the results folder
 * shows, that no run of theirs is kept; whether one is still sealed is the engines'
 * file's to say ({@code Seals}).
 */
final class RegisteredBlock {

	/** What the block says once every registered experiment has a kept run. */
	static final String ALL_MEASURED = "_Every registered held-out experiment has been measured._";

	private RegisteredBlock() {
	}

	/**
	 * The experiments that wait to be measured.
	 * @param awaiting the held-out experiments without a kept run, in the page's order
	 * ({@link LatestRuns#awaitingMeasurement})
	 * @return the table, and under it how many there are
	 */
	static String of(List<Experiment> awaiting) {
		if (awaiting.isEmpty()) {
			return ALL_MEASURED;
		}
		List<String> lines = new ArrayList<>(
				List.of("| Experiment | What it runs | Registered |", "| --- | --- | --- |"));
		for (Experiment experiment : awaiting) {
			HeldOut registration = Objects.requireNonNull(experiment.heldOut(), experiment.id());
			lines.add("| " + experiment.id() + " | " + experiment.title() + " | " + registration.registered() + " |");
		}
		boolean onlyOne = awaiting.size() == 1;
		lines.add("");
		lines.add(awaiting.size() + (onlyOne ? " held-out experiment is" : " held-out experiments are")
				+ " registered with no kept run: nothing on this page is measured on " + (onlyOne ? "it." : "them."));
		return String.join("\n", lines);
	}

}
