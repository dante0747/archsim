package io.archsim.engine.report;

import org.jspecify.annotations.Nullable;

/**
 * How far a run can be trusted, as far as the engine reads it of the run's report; each
 * {@code null} when the report does not say.
 *
 * @param level {@code high}, {@code medium} or {@code low}
 * @param reason the reason that decided the level
 * @param howToRaise what would raise it
 */
public record ReportConfidence(@Nullable String level, @Nullable String reason, @Nullable String howToRaise) {

}
