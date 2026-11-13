package io.archsim.benchmarks.range;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.HarnessLog;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.benchmarks.values.PythonValues;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * The fit's finished work, kept in a file as each task's result arrives, so that a fit
 * cut short starts again where it stopped.
 */
class RangeCacheTests {

	private static final Clock NOON = Clock.fixed(Instant.parse("2026-11-13T12:34:56Z"), ZoneOffset.UTC);

	private static final Duration WAIT = Duration.ofSeconds(30);

	@Test
	void run_tasks_keepsEachResultInTheFile(@TempDir Path folder) {
		Path file = folder.resolve("cache.json");

		RangeCache.in(file, log(new StringWriter())).run(tasks(3, new AtomicInteger()));

		assertThat(ResultFiles.read(file).orElseThrow().propertyNames()).containsExactlyInAnyOrder("task|0", "task|1",
				"task|2");
	}

	@Test
	void run_aCacheThatHoldsSomeResults_runsOnlyTheRest(@TempDir Path folder) {
		Path file = folder.resolve("cache.json");
		RangeCache.in(file, log(new StringWriter())).run(tasks(2, new AtomicInteger()));
		AtomicInteger ran = new AtomicInteger();

		RangeCache.in(file, log(new StringWriter())).run(tasks(5, ran));

		assertThat(ran).hasValue(3);
	}

	@Test
	void run_twelveTasks_saysHowFarItHasGotEveryTenthAndAtTheEnd(@TempDir Path folder) {
		StringWriter log = new StringWriter();

		RangeCache.in(folder.resolve("cache.json"), log(log)).run(tasks(12, new AtomicInteger()));

		assertThat(log.toString().lines()).containsExactly("[12:34:56] range fit: 10 of 12 done",
				"[12:34:56] range fit: 12 of 12 done");
	}

	@Test
	void get_aKeptResult_isTheResult(@TempDir Path folder) {
		RangeCache cache = RangeCache.in(folder.resolve("cache.json"), log(new StringWriter()));

		cache.run(tasks(1, new AtomicInteger()));

		assertThat(cache.get("task|0")).contains(PythonValues.integer(0));
	}

	@Test
	void in_aFileCutShort_holdsNothing(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("cache.json"), "{\"task|0\": 0,");

		RangeCache cache = RangeCache.in(file, log(new StringWriter()));

		assertThat(cache.has("task|0")).isFalse();
	}

	@Test
	void in_aFileCutShort_saysItStartsAfresh(@TempDir Path folder) throws IOException {
		Path file = Files.writeString(folder.resolve("cache.json"), "{\"task|0\": 0,");
		StringWriter log = new StringWriter();

		RangeCache.in(file, log(log));

		assertThat(log.toString()).startsWith("[12:34:56] range fit: " + file + " is not a cache (")
			.endsWith("): starting afresh" + System.lineSeparator());
	}

	@Test
	void in_aFileWithResults_saysHowManyItReuses(@TempDir Path folder) {
		Path file = folder.resolve("cache.json");
		RangeCache.in(file, log(new StringWriter())).run(tasks(2, new AtomicInteger()));
		StringWriter log = new StringWriter();

		RangeCache.in(file, log(log));

		assertThat(log.toString().lines())
			.containsExactly("[12:34:56] range fit: reusing the 2 results " + file + " holds");
	}

	@Test
	void run_aFailingTask_keepsWhatFinishedBeforeItAndThrowsIt(@TempDir Path folder) {
		Path file = folder.resolve("cache.json");
		RangeCache cache = RangeCache.in(file, log(new StringWriter()));

		Throwable thrown = catchThrowable(() -> cache.run(failingAfterTheFirst(cache)));

		assertThat(thrown).isInstanceOf(IllegalStateException.class).hasMessage("the second task failed");
		assertThat(ResultFiles.read(file).orElseThrow().propertyNames()).containsExactly("task|0");
	}

	@Test
	void run_tasks_leavesNoFileBesideTheCache(@TempDir Path folder) throws IOException {
		RangeCache.in(folder.resolve("cache.json"), log(new StringWriter())).run(tasks(3, new AtomicInteger()));

		try (Stream<Path> files = Files.list(folder)) {
			assertThat(files.map((file) -> file.getFileName().toString())).containsExactly("cache.json");
		}
	}

	// the first task, then a second that fails once the cache has kept the first
	private static List<RangeCache.Task> failingAfterTheFirst(RangeCache cache) {
		return List.of(new RangeCache.Task("task|0", () -> PythonValues.integer(0)),
				new RangeCache.Task("task|1", () -> failOnceKept(cache, "task|0")));
	}

	private static JsonNode failOnceKept(RangeCache cache, String key) {
		Instant deadline = Instant.now().plus(WAIT);
		while (!cache.has(key) && Instant.now().isBefore(deadline)) {
			Thread.onSpinWait();
		}
		throw new IllegalStateException("the second task failed");
	}

	private static List<RangeCache.Task> tasks(int count, AtomicInteger ran) {
		return IntStream.range(0, count).mapToObj((index) -> new RangeCache.Task("task|" + index, () -> {
			ran.incrementAndGet();
			return PythonValues.integer(index);
		})).toList();
	}

	private static HarnessLog log(StringWriter out) {
		return new HarnessLog(new PrintWriter(out), NOON);
	}

}
