package io.archsim.tools.release;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release workflow as the repository has it (ADR-0077): it checks its tag with
 * {@link CheckRelease}, run from its source as the workflow runs it, and the checkout
 * releases its own version to the registry the workflow pushes to.
 */
class ReleaseWorkflowTests {

	private static final Path ROOT = Path.of(System.getProperty("archsim.repoRoot", "..")).toAbsolutePath().normalize();

	private static final Path SOURCE = Path.of("src/main/java/io/archsim/tools/release/CheckRelease.java")
		.toAbsolutePath();

	private static final String CHECK = "java archsim-tools/src/main/java/io/archsim/tools/release/CheckRelease.java"
			+ " --tag \"$GITHUB_REF_NAME\" --registry \"$REGISTRY\" --github-output \"$GITHUB_OUTPUT\"";

	@Test
	void versionJob_always_checksTheTagWithCheckRelease() throws IOException {
		Map<?, ?> versionJob = mapping(mapping(workflow().get("jobs")).get("version"));

		List<String> scripts = steps(versionJob).stream()
			.map((step) -> String.valueOf(mapping(step).get("run")).strip())
			.toList();

		assertThat(scripts).contains(CHECK);
	}

	@Test
	void checkout_asItIs_releasesItsVersionToTheWorkflowsRegistry() throws IOException {
		StringWriter err = new StringWriter();

		int exitCode = CheckRelease.run(List.of("--tag", "v" + Checkout.gradleVersion(ROOT), "--registry", registry()),
				ROOT, new PrintWriter(new StringWriter()), new PrintWriter(err, true));

		assertThat(exitCode).as(err.toString()).isZero();
	}

	@Test
	void launch_fromItsSource_runsWithTheJdkAlone(@TempDir Path folder) throws IOException, InterruptedException {
		Files.copy(ROOT.resolve("gradle.properties"), folder.resolve("gradle.properties"));
		Files.copy(ROOT.resolve("docker-compose.yml"), folder.resolve("docker-compose.yml"));
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");

		Process process = new ProcessBuilder(java.toString(), SOURCE.toString(), "--tag",
				"v" + Checkout.gradleVersion(ROOT), "--registry", registry())
			.directory(folder.toFile())
			.redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.DISCARD)
			.start();

		assertThat(process.waitFor()).isZero();
	}

	private static Map<?, ?> workflow() throws IOException {
		return mapping(new Yaml()
			.load(Files.readString(ROOT.resolve(".github/workflows/release.yml"), StandardCharsets.UTF_8)));
	}

	// where the workflow pushes the images
	private static String registry() throws IOException {
		return String.valueOf(mapping(workflow().get("env")).get("REGISTRY"));
	}

	private static Map<?, ?> mapping(@Nullable Object node) {
		if (node instanceof Map<?, ?> mapping) {
			return mapping;
		}
		throw new IllegalStateException("release.yml: not a mapping: " + node);
	}

	private static List<?> steps(Map<?, ?> job) {
		if (job.get("steps") instanceof List<?> steps) {
			return steps;
		}
		throw new IllegalStateException("release.yml: a job without steps: " + job);
	}

}
