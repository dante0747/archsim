package io.archsim.engine.version;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * ArchSim's version is said once, as Gradle's {@code version} in
 * {@code gradle.properties} (ADR-0076), and the code reads what the build wrote from it.
 * The web app's package repeats it, which its own tests check.
 */
class ArchsimVersionTests {

	@Test
	void product_built_isGradlesVersion() throws IOException {
		Properties gradle = new Properties();
		try (Reader properties = Files.newBufferedReader(Repository.root().resolve("gradle.properties"))) {
			gradle.load(properties);
		}

		assertThat(ArchsimVersion.PRODUCT).isEqualTo(gradle.getProperty("version"));
	}

	@ParameterizedTest
	@ValueSource(strings = { "1.0.0", "1.2.0-rc.1" })
	void versionIn_aSemanticVersion_returnsIt(String version) {
		Properties written = new Properties();
		written.setProperty("version", version);

		assertThat(ArchsimVersion.versionIn(written)).isEqualTo(version);
	}

	// what a build that copies the file without writing the version into it leaves, and
	// Gradle's version when none is set
	@ParameterizedTest
	@ValueSource(strings = { "${version}", "unspecified", "" })
	void versionIn_noVersionWritten_isRefusedWithWhy(String version) {
		Properties written = new Properties();
		written.setProperty("version", version);

		assertThatIllegalStateException().isThrownBy(() -> ArchsimVersion.versionIn(written))
			.withMessage("archsim.properties beside io.archsim.engine.version.ArchsimVersion holds '%s', "
					+ "not a version: build with Gradle, which writes it from gradle.properties", version);
	}

}
