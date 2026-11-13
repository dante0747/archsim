package io.archsim.benchmarks.resimulation;

import org.jspecify.annotations.Nullable;

import io.archsim.engine.version.EngineVersions;

/**
 * The folder under {@code results/dev/} that development checks are written to: the
 * engine's version, or a label that starts with it ({@code 1.0.0-port} for checks kept
 * apart from earlier ones). It never names another engine's, so it never writes over the
 * frozen engine's checks ({@code 0.16.0-port}) or the Java engine's record from before
 * its version ({@code 0.16.0-java}).
 */
public final class DevLabels {

	private DevLabels() {
	}

	/**
	 * The label checks are written under.
	 * @param label the label asked for, or {@code null} for the engine's own
	 * @return the label
	 * @throws IllegalArgumentException when the label does not name this engine
	 */
	public static String of(@Nullable String label) {
		if (label == null) {
			return EngineVersions.ENGINE;
		}
		if (!label.startsWith(EngineVersions.ENGINE + "-")) {
			throw new IllegalArgumentException("a development check's label starts with " + EngineVersions.ENGINE
					+ "- and names this engine: not " + label);
		}
		return label;
	}

}
