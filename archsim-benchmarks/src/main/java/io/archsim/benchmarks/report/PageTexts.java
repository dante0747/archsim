package io.archsim.benchmarks.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.benchmarks.experiments.Protocol;
import io.archsim.benchmarks.values.PythonValues;
import io.archsim.model.text.PythonFormat;

/**
 * The words the validation page and {@code accuracy.json} put beside the numbers, from
 * {@code texts.json}: each experiment's plain name and technologies, each measured
 * container's name, why the model has no figure for some, how the resources were
 * measured, and the known flaws of the harness that limited a run.
 */
final class PageTexts {

	private static final String RESOURCE = "texts.json";

	/** Where {@code measuredBy} texts name the seconds between readings. */
	private static final String SAMPLE_SECONDS = "{sampleSeconds}";

	private static final PageTexts STANDARD = read();

	private final Catalog catalog;

	private PageTexts(Catalog catalog) {
		this.catalog = catalog;
	}

	static PageTexts standard() {
		return STANDARD;
	}

	/**
	 * The plain name of an experiment, for the web page.
	 * @param experiment the experiment's id
	 * @return its name
	 */
	String name(String experiment) {
		return this.catalog.names().getOrDefault(experiment, experiment);
	}

	/**
	 * The technologies an experiment exercises.
	 * @param experiment the experiment's id
	 * @return their names
	 */
	List<String> technologies(String experiment) {
		return this.catalog.technologies().getOrDefault(experiment, List.of());
	}

	/**
	 * A measured container as the resources table names it.
	 * @param container the container's compose service
	 * @return its name
	 */
	String part(String container) {
		return this.catalog.parts().getOrDefault(container, container);
	}

	/**
	 * A container as the environment's pinning names it.
	 * @param container the container's compose service
	 * @return its name
	 */
	String container(String container) {
		return this.catalog.containers().getOrDefault(container, container);
	}

	/**
	 * Why the model has no busy cores for a container.
	 * @param container the container's compose service
	 * @return why; empty when the model has them
	 */
	Optional<String> whyNoCores(String container) {
		return Optional.ofNullable(this.catalog.notModelled().get(container));
	}

	/**
	 * Why the model has no memory for a container.
	 * @param container the container's compose service
	 * @return why; empty when the model has it
	 */
	Optional<String> whyNoMemory(String container) {
		return whyNoCores(container).or(() -> Optional.ofNullable(this.catalog.noMemoryModel().get(container)));
	}

	/**
	 * How a run's resources were measured, in words.
	 * @param method the comparison's {@code measuredBy}
	 * @return the words
	 */
	String measuredBy(String method) {
		String text = this.catalog.measuredBy().get(method);
		return (text != null) ? text.replace(SAMPLE_SECONDS, PythonFormat.format(Protocol.SAMPLE_S, "g")) : method;
	}

	/**
	 * A known flaw of the harness that limited an experiment's run, when the run was
	 * calibrated before it was fixed.
	 * @param experiment the experiment
	 * @param calibratedAt when the run was calibrated, or None
	 * @return the flaw's text; empty when none applies
	 */
	Optional<String> harnessCaveat(String experiment, JsonNode calibratedAt) {
		Caveat caveat = this.catalog.harnessCaveats().get(experiment);
		if (caveat == null || PythonValues.isNone(calibratedAt)
				|| calibratedAt.stringValue("").compareTo(caveat.before()) >= 0) {
			return Optional.empty();
		}
		return Optional.of(caveat.text());
	}

	private static PageTexts read() {
		try (InputStream in = PageTexts.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			return new PageTexts(JsonMapper.builder().build().readValue(in, Catalog.class));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private record Catalog(Map<String, String> names, Map<String, List<String>> technologies, Map<String, String> parts,
			Map<String, String> notModelled, Map<String, String> noMemoryModel, Map<String, String> measuredBy,
			Map<String, String> containers, Map<String, Caveat> harnessCaveats) {

	}

	/**
	 * A flaw of the harness and when it was fixed.
	 *
	 * @param before the time it was fixed (UTC, ISO 8601): runs calibrated before it have
	 * it
	 * @param text what it was and what it did
	 */
	private record Caveat(String before, String text) {

	}

}
