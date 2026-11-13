package io.archsim.benchmarks;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import picocli.CommandLine;

import io.archsim.benchmarks.parity.ParityRuns;
import io.archsim.benchmarks.results.DevChecks;
import io.archsim.benchmarks.results.KneePairs;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code java-parity} in a repository of its own: a parity report, both engines' check of
 * one calibration, and both engines' speed unless a test says otherwise.
 */
class JavaParityCommandTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:00:00Z"), ZoneOffset.UTC);

	@Test
	void javaParity_thePageJustWritten_passesTheCheck(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		commandLine(layout, new StringWriter()).execute("java-parity");

		int exitCode = commandLine(layout, new StringWriter()).execute("java-parity", "--check");

		assertThat(exitCode).isZero();
	}

	@Test
	void javaParity_aPageEditedSince_failsTheCheckSayingHowToWriteIt(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		commandLine(layout, new StringWriter()).execute("java-parity");
		Path page = layout.validation().resolve("java-parity.md");
		Files.writeString(page, Files.readString(page, StandardCharsets.UTF_8) + "edited\n", StandardCharsets.UTF_8);
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("java-parity", "--check");

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString())
			.contains("java-parity.md is stale: run ./gradlew :archsim-benchmarks:run --args=\"java-parity\"");
	}

	@Test
	void javaParity_neitherEnginesSpeed_writesAPageSayingSpeedIsNotMeasured(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		Files.delete(layout.benchmarks().resolve("perf/python-0.16.0.json"));
		Files.delete(layout.benchmarks().resolve("perf/java-port.json"));

		int exitCode = commandLine(layout, new StringWriter()).execute("java-parity");

		assertThat(exitCode).isZero();
		assertThat(layout.validation().resolve("java-parity.md")).content(StandardCharsets.UTF_8)
			.contains("Not yet measured");
	}

	// nothing times the frozen engine again: its script went with it
	@Test
	void javaParity_theFrozenEnginesSpeedMissing_saysToRestoreIt(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		Files.delete(layout.benchmarks().resolve("perf/python-0.16.0.json"));
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("java-parity");

		assertThat(exitCode).isEqualTo(1);
		String restore = "git checkout HEAD -- benchmarks/perf/python-0.16.0.json";
		assertThat(err.toString()).contains("python-0.16.0.json is missing: restore it with " + restore);
	}

	@Test
	void javaParity_theJavaEnginesSpeedMissing_saysHowToMeasureIt(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		Files.delete(layout.benchmarks().resolve("perf/java-port.json"));
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("java-parity");

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).contains("java-port.json is missing: run ./gradlew :archsim-engine:speed "
				+ "-Parchsim.speed.out=benchmarks/perf/java-port.json -Parchsim.speed.repeats=5");
	}

	// no engine writes them again: the Java engine wrote its own before
	// it was 1.0.0, and the harness no longer runs the frozen engine
	@ParameterizedTest
	@ValueSource(strings = { "0.16.0-port", "0.16.0-java" })
	void javaParity_anEnginesKneesMissing_saysToRestoreThemFromGit(String knees, @TempDir Path root)
			throws IOException {
		RepositoryLayout layout = repository(root);
		deleteFolder(layout.results().resolve("dev").resolve(knees));
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("java-parity");

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString())
			.contains(knees + " is missing: restore it with git checkout HEAD -- benchmarks/results/dev/" + knees);
	}

	@Test
	void javaParity_neitherEnginesSpeed_passesTheCheck(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		Files.delete(layout.benchmarks().resolve("perf/python-0.16.0.json"));
		Files.delete(layout.benchmarks().resolve("perf/java-port.json"));
		commandLine(layout, new StringWriter()).execute("java-parity");

		int exitCode = commandLine(layout, new StringWriter()).execute("java-parity", "--check");

		assertThat(exitCode).isZero();
	}

	@Test
	void javaParity_theParityReportMissing_saysHowToWriteIt(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		Files.delete(layout.validation().resolve("simulation-parity.json"));
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("java-parity");

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).contains("simulation-parity.json is missing: run ./gradlew :archsim-engine:parity "
				+ "-Parchsim.parity.out=docs/validation");
	}

	@Test
	void javaParity_noPageYet_failsTheCheck(@TempDir Path root) throws IOException {
		RepositoryLayout layout = repository(root);
		StringWriter err = new StringWriter();

		int exitCode = commandLine(layout, err).execute("java-parity", "--check");

		assertThat(exitCode).isEqualTo(1);
		assertThat(err.toString()).contains("java-parity.md is stale");
	}

	private static RepositoryLayout repository(Path root) throws IOException {
		RepositoryLayout layout = new RepositoryLayout(root);
		Files.createDirectories(layout.validation());
		Files.writeString(layout.validation().resolve("simulation-parity.json"),
				ParityRuns.parity(ParityRuns.met()).toString(), StandardCharsets.UTF_8);
		for (String engine : List.of(KneePairs.PYTHON_KNEES, KneePairs.JAVA_KNEES)) {
			DevChecks.write(layout.results().resolve("dev"), engine,
					DevChecks.Check.of("2026-10-26T08-23-18Z", 1000.0));
		}
		Path perf = layout.benchmarks().resolve("perf");
		Files.createDirectories(perf);
		Files.writeString(perf.resolve("python-0.16.0.json"), "{\"templates\": []}", StandardCharsets.UTF_8);
		Files.writeString(perf.resolve("java-port.json"), "{\"templates\": []}", StandardCharsets.UTF_8);
		return layout;
	}

	private static void deleteFolder(Path folder) throws IOException {
		try (Stream<Path> files = Files.walk(folder)) {
			for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.delete(file);
			}
		}
	}

	private static CommandLine commandLine(RepositoryLayout layout, StringWriter err) {
		PrintWriter out = new PrintWriter(new StringWriter(), true);
		return Benchmarks.commandLine(layout, new HarnessLog(out, NOON)).setOut(out).setErr(new PrintWriter(err, true));
	}

}
