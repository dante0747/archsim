package io.archsim.engine.report;

import java.util.Objects;

/**
 * The design a run simulated, as far as the engine reads it of the run's report.
 *
 * @param metadata the design's metadata
 */
public record ReportInput(ReportMetadata metadata) {

	/**
	 * Checks that the design has its metadata.
	 */
	public ReportInput {
		Objects.requireNonNull(metadata, "a report's input has its metadata");
	}

}
