package io.archsim.engine.version;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * ArchSim's own version, the product's: what a release is tagged with, and what
 * {@code archsim --version}, the MCP server and {@code GET /meta} name beside the
 * engine's. It moves with every release, where the engine's
 * ({@link EngineVersions#ENGINE}) moves only when what a simulation means changes, so
 * that saved runs compare fairly (ADR-0076). It is said once, as Gradle's {@code version}
 * in {@code gradle.properties}, which the build writes into {@code archsim.properties}
 * beside this class.
 */
public final class ArchsimVersion {

	/** The product's version, a semantic version such as {@code 1.0.0}. */
	public static final String PRODUCT = versionIn(written());

	// constant variables, which the compiler inlines: PRODUCT, set first, reads them
	private static final String RESOURCE = "archsim.properties";

	private static final String KEY = "version";

	private static final String WHO_WRITES_IT = "build with Gradle, which writes it from gradle.properties";

	/** A semantic version (semver.org), with a pre-release but no build metadata. */
	private static final String SEMANTIC_VERSION = "(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(-[0-9A-Za-z.-]+)?";

	private ArchsimVersion() {
	}

	/**
	 * The version the build wrote.
	 * @param written what the build wrote into {@code archsim.properties}
	 * @return its version
	 * @throws IllegalStateException when it holds no semantic version: a build that
	 * copied the file without writing the version into it, or one with no version set
	 */
	static String versionIn(Properties written) {
		String version = written.getProperty(KEY, "");
		if (!version.matches(SEMANTIC_VERSION)) {
			throw new IllegalStateException("%s beside %s holds '%s', not a version: %s".formatted(RESOURCE,
					ArchsimVersion.class.getName(), version, WHO_WRITES_IT));
		}
		return version;
	}

	private static Properties written() {
		InputStream resource = ArchsimVersion.class.getResourceAsStream(RESOURCE);
		if (resource == null) {
			throw new IllegalStateException(
					"no %s beside %s: %s".formatted(RESOURCE, ArchsimVersion.class.getName(), WHO_WRITES_IT));
		}
		Properties written = new Properties();
		try (resource) {
			written.load(resource);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return written;
	}

}
