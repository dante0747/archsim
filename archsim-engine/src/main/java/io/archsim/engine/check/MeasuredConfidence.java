package io.archsim.engine.check;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.report.ReportConfidence;

/**
 * How far a measured run can be trusted, as a check shows it: the three figures of the
 * run's confidence it repeats, each {@code null} when the report does not say.
 *
 * @param level {@code high}, {@code medium} or {@code low}
 * @param reason the reason that decided the level
 * @param howToRaise what would raise it
 */
public record MeasuredConfidence(@Nullable String level, @Nullable String reason, @Nullable String howToRaise) {

	/**
	 * The figures a check repeats of a run's confidence.
	 * @param confidence the confidence, as the run's report gives it; {@code null} when
	 * it gives none
	 * @return the figures, each {@code null} when the report does not say
	 */
	static MeasuredConfidence of(@Nullable ReportConfidence confidence) {
		if (confidence == null) {
			return new MeasuredConfidence(null, null, null);
		}
		return new MeasuredConfidence(confidence.level(), confidence.reason(), confidence.howToRaise());
	}

}
