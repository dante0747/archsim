package io.archsim.cli;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.check.CheckSettings;

/**
 * What {@code archsim check} was asked, each option as the Python CLI used it
 * ({@link PythonOptions}).
 *
 * @param design the design's file
 * @param scenario the scenario checked, as given; {@code null} or empty for the design as
 * drawn
 * @param baseline the baseline's file; {@code null} for none
 * @param baselineRef the git ref the design is compared with as it was there;
 * {@code null} for none
 * @param settings the regression thresholds and what fails the check
 * @param knee whether the breaking points are swept and compared too
 * @param format what the result is written as
 * @param out where the result is written; {@code null} for standard output
 * @param jsonOut where the JSON result is written too; {@code null} for nowhere
 * @param reportUrl the full report's link in the Markdown; {@code null} for none
 */
record CheckOptions(String design, @Nullable String scenario, @Nullable String baseline, @Nullable String baselineRef,
		CheckSettings settings, boolean knee, CheckFormat format, @Nullable String out, @Nullable String jsonOut,
		@Nullable String reportUrl) {

}
