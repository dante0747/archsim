package io.archsim.tools.release;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release workflow's check of a tag, before it publishes the images (ADR-0077): the
 * tag names gradle.properties' version, and docker-compose.yml runs the images the
 * workflow pushes, at that version.
 */
class CheckReleaseTests {

	private static final String REGISTRY = "ghcr.io/dante0747";

	private final StringWriter out = new StringWriter();

	private final StringWriter err = new StringWriter();

	@TempDir
	private Path repository;

	@Test
	void run_aTagNamingTheVersion_writesTheVersionAndMovesLatest() throws IOException {
		checkout("version=1.0.0", "1.0.0");
		Path output = this.repository.resolve("github-output");

		int exitCode = run("--tag", "v1.0.0", "--registry", REGISTRY, "--github-output", output.toString());

		assertThat(exitCode).isZero();
		assertThat(Files.readString(output)).isEqualTo("version=1.0.0\nlatest=true\n");
	}

	@Test
	void run_aPreRelease_leavesLatestWhereItIs() throws IOException {
		checkout("version=1.1.0-rc.1", "1.1.0-rc.1");
		Path output = this.repository.resolve("github-output");

		int exitCode = run("--tag", "v1.1.0-rc.1", "--registry", REGISTRY, "--github-output", output.toString());

		assertThat(exitCode).isZero();
		assertThat(Files.readString(output)).isEqualTo("version=1.1.0-rc.1\nlatest=false\n");
	}

	@Test
	void run_aVersionSpacedOut_isReadAsGradleReadsIt() throws IOException {
		checkout("version = 1.0.0", "1.0.0");

		assertThat(run("--tag", "v1.0.0", "--registry", REGISTRY)).isZero();
	}

	@Test
	void run_aTagNamingAnotherVersion_failsSaysWhyAndWritesNothing() throws IOException {
		checkout("version=1.0.0", "1.0.0");
		Path output = this.repository.resolve("github-output");

		int exitCode = run("--tag", "v1.0.1", "--registry", REGISTRY, "--github-output", output.toString());

		assertThat(exitCode).isOne();
		assertThat(output).doesNotExist();
		assertThat(this.err.toString().lines())
			.containsExactly("the tag v1.0.1 does not name gradle.properties' version, 1.0.0");
	}

	@Test
	void run_composeAtAnotherVersion_failsAndSaysWhatItShouldRun() throws IOException {
		checkout("version=1.1.0", "1.0.0");

		int exitCode = run("--tag", "v1.1.0", "--registry", REGISTRY);

		assertThat(exitCode).isOne();
		assertThat(this.err.toString().lines()).containsExactly(
				"docker-compose.yml does not run ghcr.io/dante0747/archsim-app:1.1.0",
				"docker-compose.yml does not run ghcr.io/dante0747/archsim-web:1.1.0");
	}

	@Test
	void run_composeFromAnotherRegistry_fails() throws IOException {
		checkout("version=1.0.0", "1.0.0");

		int exitCode = run("--tag", "v1.0.0", "--registry", "ghcr.io/another-owner");

		assertThat(exitCode).isOne();
	}

	@Test
	void run_anImageInACommentOnly_isNotOneComposeRuns() throws IOException {
		checkout("version=1.0.0", "1.0.0");
		String compose = Files.readString(this.repository.resolve("docker-compose.yml"));
		Files.writeString(this.repository.resolve("docker-compose.yml"), compose.replace(
				"    image: ghcr.io/dante0747/archsim-web:1.0.0", "    # image: ghcr.io/dante0747/archsim-web:1.0.0"));

		int exitCode = run("--tag", "v1.0.0", "--registry", REGISTRY);

		assertThat(exitCode).isOne();
		assertThat(this.err.toString().lines())
			.containsExactly("docker-compose.yml does not run ghcr.io/dante0747/archsim-web:1.0.0");
	}

	@Test
	void run_withoutARegistry_isMisused() throws IOException {
		checkout("version=1.0.0", "1.0.0");

		int exitCode = run("--tag", "v1.0.0");

		assertThat(exitCode).isEqualTo(2);
		assertThat(this.err.toString().lines()).containsExactly(CheckRelease.USAGE,
				"CheckRelease: error: the following arguments are required: --registry");
	}

	@Test
	void run_anUnknownOption_isMisused() throws IOException {
		checkout("version=1.0.0", "1.0.0");

		int exitCode = run("--tag", "v1.0.0", "--registry", REGISTRY, "--push", "yes");

		assertThat(exitCode).isEqualTo(2);
		assertThat(this.err.toString().lines()).containsExactly(CheckRelease.USAGE,
				"CheckRelease: error: unrecognized arguments: --push");
	}

	@Test
	void run_anOptionWithoutItsValue_isMisused() throws IOException {
		checkout("version=1.0.0", "1.0.0");

		int exitCode = run("--registry", REGISTRY, "--tag");

		assertThat(exitCode).isEqualTo(2);
		assertThat(this.err.toString().lines()).containsExactly(CheckRelease.USAGE,
				"CheckRelease: error: argument --tag: expected one argument");
	}

	@Test
	void run_aReleasableTag_saysWhatTheReleaseDoes() throws IOException {
		checkout("version=1.1.0-rc.1", "1.1.0-rc.1");

		run("--tag", "v1.1.0-rc.1", "--registry", REGISTRY);

		assertThat(this.out.toString().lines()).containsExactly("release 1.1.0-rc.1, leaving latest");
	}

	@Test
	void run_aCheckoutWithoutCompose_failsAndSaysWhy() throws IOException {
		checkout("version=1.0.0", "1.0.0");
		Files.delete(this.repository.resolve("docker-compose.yml"));

		int exitCode = run("--tag", "v1.0.0", "--registry", REGISTRY);

		assertThat(exitCode).isOne();
		assertThat(this.err.toString()).startsWith("CheckRelease: java.nio.file.NoSuchFileException: ")
			.contains("docker-compose.yml");
	}

	// a checkout's gradle.properties with a version line, and docker-compose.yml with
	// ArchSim's images at a version
	private void checkout(String versionLine, String composeVersion) throws IOException {
		Files.writeString(this.repository.resolve("gradle.properties"),
				"org.gradle.caching=true\n" + versionLine + "\n");
		Files.writeString(this.repository.resolve("docker-compose.yml"), """
				name: archsim
				x-archsim-app: &archsim-app
				  image: ghcr.io/dante0747/archsim-app:%1$s
				services:
				  postgres:
				    image: postgres:16-alpine
				  web:
				    image: ghcr.io/dante0747/archsim-web:%1$s
				""".formatted(composeVersion));
	}

	private int run(String... arguments) {
		return CheckRelease.run(List.of(arguments), this.repository, new PrintWriter(this.out, true),
				new PrintWriter(this.err, true));
	}

}
