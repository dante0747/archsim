package io.archsim.app.runs;

import org.jspecify.annotations.Nullable;

import io.archsim.app.store.Text;

/**
 * What runs are called: the label a request gives, else the scenario's name, else the
 * design's, cut to the longest a label may be.
 */
final class RunLabels {

	/** The longest label a run has. */
	static final int MAX_LABEL = 200;

	/** What a run of the design itself is called. */
	private static final String CURRENT_DESIGN = "Current design";

	private RunLabels() {
	}

	/**
	 * A run's label: the requested one, else its scenario's name or the design's, and so
	 * for a quick check.
	 * @param requested the label the request gives, if any
	 * @param prepared the run
	 * @param mode how it is simulated
	 * @return the label
	 */
	static String of(@Nullable String requested, PlannedRun prepared, RunMode mode) {
		if (requested != null && !requested.isEmpty()) {
			return requested;
		}
		String name = named(null, prepared.scenarioName());
		return (mode == RunMode.SAMPLED) ? name + " · quick check" : name;
	}

	/**
	 * A label: the requested one, else the scenario's name, else the design's.
	 * @param requested the label the request gives, if any
	 * @param scenarioName the scenario's name, if a scenario is run
	 * @return the label
	 */
	static String named(@Nullable String requested, @Nullable String scenarioName) {
		if (requested != null && !requested.isEmpty()) {
			return requested;
		}
		return (scenarioName != null) ? scenarioName : CURRENT_DESIGN;
	}

	/**
	 * A label cut to the longest a label may be.
	 * @param text the label
	 * @return its first 200 characters
	 */
	static String cut(String text) {
		return Text.cut(text, MAX_LABEL);
	}

}
