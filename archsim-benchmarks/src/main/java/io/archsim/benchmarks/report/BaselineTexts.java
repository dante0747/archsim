package io.archsim.benchmarks.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

/**
 * The words of the validation page's "Against simple estimates" block, from
 * {@code baseline-texts.json}: what each estimate is, in plain words, the name of each
 * column, and the sentences under the table.
 */
final class BaselineTexts {

	private static final String RESOURCE = "baseline-texts.json";

	private static final BaselineTexts STANDARD = read();

	private final Catalog catalog;

	private BaselineTexts(Catalog catalog) {
		this.catalog = catalog;
	}

	static BaselineTexts standard() {
		return STANDARD;
	}

	/**
	 * What the table shows and what each estimate is.
	 * @return the paragraph and the list above the table
	 */
	String intro() {
		return String.join("\n", this.catalog.intro());
	}

	/**
	 * The title of the simulator's or an estimate's column.
	 * @param estimate {@code simulator}, or an estimate's key in {@code baselines.json}
	 * @return the title
	 */
	String name(String estimate) {
		return this.catalog.names().getOrDefault(estimate, estimate);
	}

	/**
	 * That the simulator's knees are predictions, and when and by which engine the
	 * estimates were computed.
	 * @param dates the days the runs' baselines were computed
	 * @param engines the engine versions that estimated them
	 * @return the sentences
	 */
	String computed(String dates, String engines) {
		return this.catalog.computed().replace("{dates}", dates).replace("{engines}", engines);
	}

	/**
	 * When the estimates' rules were fixed, and that for the runs calibrated before then
	 * they are no predictions.
	 * @param rulesFixed the day the rules were fixed
	 * @return the sentence
	 */
	String rules(String rulesFixed) {
		return this.catalog.rules().replace("{rulesFixed}", rulesFixed);
	}

	/**
	 * Which runs shown were calibrated after the rules were fixed, so that the rules
	 * predate their measurement.
	 * @param experiments their experiments, as a sentence lists them
	 * @return the sentence
	 */
	String later(String experiments) {
		return this.catalog.later().replace("{experiments}", experiments);
	}

	/**
	 * Where every figure is, and how it was found.
	 * @return the sentence
	 */
	String files() {
		return this.catalog.files();
	}

	/**
	 * What the engine's estimate says at a knee where it calls the design overloaded.
	 * @param experiment the experiment
	 * @param summary the estimate's own summary at its knee
	 * @return the sentence
	 */
	String overloaded(String experiment, String summary) {
		return this.catalog.overloaded().replace("{experiment}", experiment).replace("{summary}", summary);
	}

	/**
	 * Which experiments' latest runs have no baselines, and the command that writes them.
	 * @param experiments the experiments, as a sentence lists them
	 * @return the sentence
	 */
	String missing(String experiments) {
		return this.catalog.missing().replace("{experiments}", experiments);
	}

	private static BaselineTexts read() {
		try (InputStream in = BaselineTexts.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IllegalStateException("missing resource " + RESOURCE);
			}
			return new BaselineTexts(JsonMapper.builder().build().readValue(in, Catalog.class));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	private record Catalog(List<String> intro, Map<String, String> names, String computed, String rules, String later,
			String files, String overloaded, String missing) {

	}

}
