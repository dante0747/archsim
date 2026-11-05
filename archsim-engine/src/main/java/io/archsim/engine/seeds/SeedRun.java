package io.archsim.engine.seeds;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import io.archsim.engine.slo.RunFigures;
import io.archsim.model.text.PythonText;

/**
 * One run of a group that differ only in their seed.
 *
 * @param seed the run's seed
 * @param figures its figures once it finished; {@code null} while it is queued or
 * running, or when it failed
 */
public record SeedRun(long seed, @Nullable RunFigures figures) {

	/**
	 * A run with the summary it is stored with, as the Python engine counted it: finished
	 * once it has a summary that is not empty, in either shape ({@link RunFigures#read}).
	 * @param seed the run's seed
	 * @param summary its summary, or {@code null} while it has none
	 * @return the run
	 */
	public static SeedRun read(long seed, @Nullable JsonNode summary) {
		if (summary == null || !PythonText.truthy(summary)) {
			return new SeedRun(seed, null);
		}
		return new SeedRun(seed, RunFigures.read(summary));
	}

}
