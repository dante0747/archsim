package io.archsim.benchmarks.resimulation;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Simulations;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kept runs checked in parallel and written in their order under the label's folder, a
 * run calibrated from one pass skipped.
 */
class ResimulationTests {

	private static final Path RESULTS = RepositoryLayout.current().results();

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	private static final String LABEL = "1.0.0-port";

	@Test
	void resimulate_aKeptRun_writesItsCheckUnderTheLabel(@TempDir Path results) throws IOException {
		KeptRun run = copy("kafka/run-2", results);

		List<Path> written = resimulation(results, new StringWriter()).resimulate(List.of(run));

		assertThat(written).containsExactly(results.resolve("dev/1.0.0-port/kafka/2026-10-30T11-20-01Z.json"));
		assertThat(ResultFiles.read(written.getFirst()).orElseThrow().get("engineVersion").stringValue())
			.isEqualTo(LABEL);
	}

	@Test
	void resimulate_aRunCalibratedFromOnePass_isSkippedSayingSo(@TempDir Path results) throws IOException {
		KeptRun run = copy("pg-mixed/run-1", results);
		StringWriter log = new StringWriter();

		List<Path> written = resimulation(results, log).resimulate(List.of(run));

		assertThat(written).isEmpty();
		assertThat(log.toString().lines()).containsExactly(
				"[12:34:56] checking 1 kept runs with engine 1.0.0-port (in-sample)",
				"[12:34:56] pg-mixed (run-1): skipped, no two-pass calibration");
	}

	@Test
	void resimulate_aKeptRun_logsItsKnees(@TempDir Path results) throws IOException {
		KeptRun run = copy("kafka/run-2", results);
		StringWriter log = new StringWriter();

		resimulation(results, log).resimulate(List.of(run));

		assertThat(log.toString().lines()).last()
			.asString()
			.matches("\\[12:34:56] kafka \\(run-2\\): knee measured \\S+, simulated \\S+ \\(error \\S+\\); "
					+ "inputs as calibrated: (True|False)");
	}

	// a calibration without a pass today's inputs read: skipped, as a run whose
	// calibration Python's dev_check could not read
	@Test
	void resimulate_aRunWithoutAPassItNeeds_isSkippedSayingWhy(@TempDir Path results) throws IOException {
		KeptRun run = copy("kafka/run-2", results);
		ObjectNode calibration = (ObjectNode) ResultFiles.read(run.file(KeptRun.CALIBRATION)).orElseThrow();
		((ObjectNode) calibration.get("passes")).remove("write:1");
		ResultFiles.write(run.file(KeptRun.CALIBRATION), calibration);
		StringWriter log = new StringWriter();

		List<Path> written = resimulation(results, log).resimulate(List.of(run));

		assertThat(written).isEmpty();
		assertThat(log.toString().lines()).containsExactly(
				"[12:34:56] checking 1 kept runs with engine 1.0.0-port (in-sample)",
				"[12:34:56] kafka (run-2): today's inputs cannot be derived from it "
						+ "(calibration recorded no 'write:1' pass)",
				"[12:34:56] kafka (run-2): skipped, no two-pass calibration");
	}

	private static Resimulation resimulation(Path results, StringWriter log) {
		CheckStamp stamp = new CheckStamp(LABEL, JsonNodeFactory.instance.objectNode(), NOON);
		return new Resimulation(results, Simulations.onTheEngine(), stamp, new HarnessLog(new PrintWriter(log), NOON));
	}

	// a kept run's files, under a results folder of its own
	private static KeptRun copy(String folder, Path results) throws IOException {
		Path from = RESULTS.resolve(folder);
		Path to = results.resolve(folder);
		Files.createDirectories(to);
		for (String file : List.of("calibration.json", "measured.json", "predictions.json")) {
			Files.copy(from.resolve(file), to.resolve(file));
		}
		return new KeptRun(folder.substring(0, folder.indexOf('/')), to);
	}

}
