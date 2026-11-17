package io.archsim.engine.assumptions;

import org.jspecify.annotations.Nullable;

/**
 * An engine-wide number, with where it lives and its basis.
 *
 * @param area the model it belongs to, such as {@code Auto-sizing}
 * @param name what it is
 * @param value its value as the page shows it, with its unit
 * @param code where it lives, in the Python engine's names (such as
 * {@code sizing.TARGET_CPU}), as the page has always named them
 * @param source the documentation that states it, or the file of ArchSim's benchmark
 * results that measured it; {@code null} when it is illustrative, never when measured
 * @param measured whether ArchSim's own benchmark measured it (ADR-0085)
 */
public record EngineConstant(String area, String name, String value, String code, @Nullable String source,
		boolean measured) {

	/**
	 * Checks that a measured constant names its results.
	 * @throws IllegalArgumentException when a measured constant has no source
	 */
	public EngineConstant {
		if (measured && source == null) {
			throw new IllegalArgumentException(name + " is measured, so it names the results that measured it");
		}
	}

}
