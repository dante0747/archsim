package io.archsim.app.worker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import io.archsim.apitests.Examples;
import io.archsim.engine.yaml.YamlDocuments;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The worker's command lines (ADR-0053): wherever it is started, its JVM has a heap for
 * the runs it takes at once, by the rule of archsim-app's README, and exits when the heap
 * runs out, so its runs are requeued. The black-box suite's command is checked with the
 * suite.
 */
class WorkerCommandLineTests {

	private static final Path ROOT = Examples.repoRoot();

	private static final String EXIT_ON_OUT_OF_MEMORY = "-XX:+ExitOnOutOfMemoryError";

	/** The heap the README gives each run a worker takes at once, in MB. */
	private static final long RUN_HEAP_MB = 512;

	/** The heap it gives the application besides, in MB. */
	private static final long APP_HEAP_MB = 256;

	private static final Pattern HEAP = Pattern.compile("-Xmx(\\d+)([mg])");

	private static final long MB_IN_A_GB = 1024;

	@Test
	void compose_theWorkerService_runsTheAppWithAHeapForItsRuns() throws IOException {
		JsonNode worker = composeService("worker");

		assertThat(worker.path("image").asString()).isEqualTo("archsim-app:local");
		assertThat(worker.path("command").valueStream().map(JsonNode::asString).toList()).containsExactly("worker");
		assertThat(heapMb(worker.path("environment").path("JAVA_TOOL_OPTIONS").asString())).isGreaterThanOrEqualTo(
				heapFor(worker.path("environment").required("ARCHSIM_MAX_CONCURRENT_RUNS").asInt()));
	}

	@Test
	void compose_theWorkerService_isStartedAgainWhenItStops() throws IOException {
		assertThat(composeService("worker").path("restart").asString()).isEqualTo("on-failure");
	}

	@Test
	void compose_theApiService_isStartedAgainWhenItStops() throws IOException {
		assertThat(composeService("api").path("restart").asString()).isEqualTo("on-failure");
	}

	@Test
	void image_everyRole_exitsOnRunningOutOfHeap() throws IOException {
		String build = Files.readString(ROOT.resolve("archsim-app/build.gradle.kts"));

		assertThat(build).containsPattern("jvmFlags = listOf\\([^)]*\"" + Pattern.quote(EXIT_ON_OUT_OF_MEMORY) + "\"");
	}

	@Test
	void endToEndLauncher_theWorker_hasAHeapForItsRunsAndExitsOnRunningOut() throws IOException {
		String launcher = Files.readString(ROOT.resolve("apps/web/e2e/api-server.mjs"));
		Matcher worker = Pattern
			.compile("\\[\"(-Xmx\\d+[mg])\", \"" + Pattern.quote(EXIT_ON_OUT_OF_MEMORY)
					+ "\", \"-jar\", jar, \"worker\"\\]")
			.matcher(launcher);
		Matcher runs = Pattern.compile("ARCHSIM_MAX_CONCURRENT_RUNS: \"(\\d+)\"").matcher(launcher);

		assertThat(worker.find()).isTrue();
		assertThat(runs.find()).isTrue();
		assertThat(heapMb(worker.group(1))).isGreaterThanOrEqualTo(heapFor(Integer.parseInt(runs.group(1))));
	}

	private static JsonNode composeService(String name) throws IOException {
		return YamlDocuments.one(Files.readString(ROOT.resolve("docker-compose.yml"))).path("services").path(name);
	}

	private static long heapFor(int runs) {
		return RUN_HEAP_MB * runs + APP_HEAP_MB;
	}

	private static long heapMb(String options) {
		Matcher heap = HEAP.matcher(options);
		assertThat(heap.find()).as("a heap (-Xmx) in %s", options).isTrue();
		long size = Long.parseLong(heap.group(1));
		return "g".equals(heap.group(2)) ? size * MB_IN_A_GB : size;
	}

}
