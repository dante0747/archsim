package io.archsim.engine.report;

import java.util.Objects;

/**
 * A simulated design's metadata, as far as the engine reads it.
 *
 * @param name the design's name
 */
public record ReportMetadata(String name) {

	/**
	 * Checks that the metadata has the design's name.
	 */
	public ReportMetadata {
		Objects.requireNonNull(name, "a design's metadata has its name");
	}

}
