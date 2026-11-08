package io.archsim.engine.explain;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.numeric.PythonRound;

/**
 * One calculation behind a report's numbers: a formula written out symbolically, again
 * with the run's numbers substituted, and its result. The predictions are textbook
 * models; the simulation does not use them, and showing both lets a reader see where real
 * effects (variance, bursts, downstream waits, locks) make the simulation differ.
 *
 * @param id what the formula computes, such as {@code arrival_rate}
 * @param title its title
 * @param formula the formula, symbolically
 * @param substituted the formula with the run's numbers
 * @param result its result to four decimals, or {@code null} when it has none or none
 * that is finite
 * @param unit the result's unit, if any
 * @param note what to read beside it, such as the simulation's own measure
 * @param source where the formula comes from
 * @param metric the time series of the part it predicts
 */
public record Formula(String id, String title, String formula, String substituted, @Nullable Double result, String unit,
		@Nullable String note, @Nullable String source, @Nullable String metric) {

	/** The decimals a result is rounded to. */
	private static final int DECIMALS = 4;

	/**
	 * A formula without a result yet.
	 * @param id what it computes
	 * @param title its title
	 * @param formula the formula, symbolically
	 * @param substituted the formula with the run's numbers
	 * @return the formula
	 */
	public static Formula of(String id, String title, String formula, String substituted) {
		return new Formula(id, title, formula, substituted, null, "", null, null, null);
	}

	/**
	 * The formula with its result.
	 * @param value the result, or {@code null} when there is none
	 * @param resultUnit its unit, or empty
	 * @return the formula with the result rounded to four decimals, none when it is not
	 * finite
	 */
	public Formula withResult(@Nullable Double value, String resultUnit) {
		Double rounded = (value != null && Double.isFinite(value)) ? PythonRound.round(value, DECIMALS) : null;
		return new Formula(this.id, this.title, this.formula, this.substituted, rounded, resultUnit, this.note,
				this.source, this.metric);
	}

	/**
	 * The formula with a note.
	 * @param text the note, or {@code null} for none
	 * @return the formula with the note
	 */
	public Formula withNote(@Nullable String text) {
		return new Formula(this.id, this.title, this.formula, this.substituted, this.result, this.unit, text,
				this.source, this.metric);
	}

	/**
	 * The formula with its source.
	 * @param url where it comes from
	 * @return the formula with the source
	 */
	public Formula withSource(String url) {
		return new Formula(this.id, this.title, this.formula, this.substituted, this.result, this.unit, this.note, url,
				this.metric);
	}

	/**
	 * The formula with the series it predicts.
	 * @param series the part's time series, such as {@code arrivalRps}
	 * @return the formula with the metric
	 */
	public Formula withMetric(String series) {
		return new Formula(this.id, this.title, this.formula, this.substituted, this.result, this.unit, this.note,
				this.source, series);
	}

}
