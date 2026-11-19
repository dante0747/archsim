package io.archsim.benchmarks.baselines;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.ResultCopies;
import io.archsim.benchmarks.designs.Estimates;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.resimulation.CheckStamp;
import io.archsim.benchmarks.results.KeptRun;
import io.archsim.benchmarks.results.KeptRuns;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.engine.version.EngineVersions;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The kept runs' {@code baselines.json}, on a copy of cache-ttl's first two runs, which
 * no later campaign changes: written where there is none or its figures are not today's,
 * and left as it is otherwise.
 */
class BaselineFilesTests {

	private static final RepositoryLayout LAYOUT = RepositoryLayout.current();

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	private static final Clock LATER = Clock.fixed(Instant.parse("2026-11-14T08:00:00Z"), ZoneOffset.UTC);

	@Test
	void write_keptRunsWithoutBaselines_writesEachRunsFileBesideItsComparison(@TempDir Path results)
			throws IOException {
		List<KeptRun> runs = cacheTtlRuns(results);

		List<Path> written = files(NOON, new Logs()).write(runs);

		assertThat(written).containsExactly(results.resolve("cache-ttl/run-1/baselines.json"),
				results.resolve("cache-ttl/run-2/baselines.json"));
		assertThat(written).allSatisfy((file) -> assertThat(file).exists());
	}

	@Test
	void write_keptRuns_logsEachRunsKnees(@TempDir Path results) throws IOException {
		List<KeptRun> runs = cacheTtlRuns(results);
		Logs logs = new Logs();

		files(NOON, logs).write(runs);

		assertThat(logs.text().lines()).last()
			.isEqualTo("[12:34:56] cache-ttl (run-2): knee by bottleneck arithmetic 5514.4, by the engine's "
					+ "estimate 5450, by utilisation extrapolation 5728.0 req/s");
	}

	@Test
	void write_filesWhoseFiguresAreTodays_leavesThemAsTheyWere(@TempDir Path results) throws IOException {
		List<KeptRun> runs = cacheTtlRuns(results);
		files(NOON, new Logs()).write(runs);

		List<Path> written = files(LATER, new Logs()).write(runs);

		assertThat(written).isEmpty();
		assertThat(at(results.resolve("cache-ttl/run-2/baselines.json"))).isEqualTo("2026-11-13T12:34:56Z");
	}

	@Test
	void write_aFileWithOtherFigures_writesItAgain(@TempDir Path results) throws IOException {
		List<KeptRun> runs = cacheTtlRuns(results);
		files(NOON, new Logs()).write(runs);
		Path second = results.resolve("cache-ttl/run-2/baselines.json");
		ObjectNode stale = (ObjectNode) ResultFiles.read(second).orElseThrow();
		stale.put("calibratedAt", "2026-11-01T00:00:00Z");
		ResultFiles.write(second, stale);

		List<Path> written = files(LATER, new Logs()).write(runs);

		assertThat(written).containsExactly(second);
		assertThat(at(second)).isEqualTo("2026-11-14T08:00:00Z");
	}

	@Test
	void rewrite_filesWhoseFiguresAreTodays_stampsThemAnew(@TempDir Path results) throws IOException {
		List<KeptRun> runs = cacheTtlRuns(results);
		files(NOON, new Logs()).write(runs);

		List<Path> written = files(LATER, new Logs()).rewrite(runs);

		assertThat(written).hasSize(2);
		assertThat(at(results.resolve("cache-ttl/run-1/baselines.json"))).isEqualTo("2026-11-14T08:00:00Z");
	}

	@Test
	void write_aRunWithoutACalibration_writesNothingAndSaysSo(@TempDir Path results) throws IOException {
		Path folder = Files.createDirectories(results.resolve("cache-ttl"));
		Files.writeString(folder.resolve("measured.json"), "{}");
		Logs logs = new Logs();

		List<Path> written = files(NOON, logs).write(KeptRuns.of(results));

		assertThat(written).isEmpty();
		assertThat(logs.text().lines()).containsExactly("[12:34:56] cache-ttl (cache-ttl): skipped, no calibration");
	}

	// cache-ttl's run-1 and run-2, without the baselines the test writes
	private static List<KeptRun> cacheTtlRuns(Path results) throws IOException {
		for (String run : List.of("run-1", "run-2")) {
			ResultCopies.copyWithoutBaselines(LAYOUT.results().resolve("cache-ttl").resolve(run),
					results.resolve("cache-ttl").resolve(run));
		}
		return KeptRuns.of(results);
	}

	private static BaselineFiles files(Clock clock, Logs logs) {
		Baselines baselines = new Baselines(Estimates.onTheEngine(), Cpusets.declaredIn(LAYOUT.compose()),
				new CheckStamp(EngineVersions.ENGINE, JsonText.read("{\"commit\": \"abc\"}"), clock));
		return new BaselineFiles(baselines, logs.log());
	}

	private static String at(Path file) {
		return ResultFiles.read(file).orElseThrow().get("at").stringValue();
	}

}
