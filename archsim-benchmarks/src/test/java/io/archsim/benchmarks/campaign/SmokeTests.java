package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.docker.Cgroups;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.Cpusets;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeServices;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ProcessResult;
import io.archsim.benchmarks.docker.StackLifecycle;
import io.archsim.benchmarks.experiments.Experiment;
import io.archsim.benchmarks.experiments.Experiments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/**
 * A stack's smoke test: started, sent its traffic at 20 req/s for 10 s, checked and
 * stopped, keeping nothing.
 */
class SmokeTests {

	private static final Experiment PYTHON = Experiments.standard().get("python-mixed");

	private static final String SUMMARY = """
			{"http_req_duration{scenario:measure,expected_response:true}": {"values": {"med": 3.0, "p(95)": 9.0,
			 "count": 200}}, "http_reqs{scenario:measure}": {"values": {"count": 200}},
			 "http_req_failed{scenario:measure}": {"values": {"passes": 0}},
			 "dropped_iterations{scenario:measure}": {"values": {"count": 0}}}""";

	// a command fragment no command has
	private static final String NOTHING_FAILS = "no such command";

	private final List<String> events = Collections.synchronizedList(new ArrayList<>());

	@Test
	void run_anExperiment_startsSendsChecksAndStopsItsStack(@TempDir Path results) {
		smoke(results, 200).run(PYTHON);

		assertThat(this.events).containsExactly("--profile python-mixed down -v --remove-orphans",
				"--profile python-mixed up -d --build --wait", "GET health", "POST reset",
				"k6 TARGET=http://api-python:8080 MIX=read:0.9,write:0.1 RATE=20 WARMUP_S=0 DURATION_S=10", "GET stats",
				"--profile python-mixed down -v --remove-orphans");
	}

	@Test
	void run_anExperimentThatPassed_findsNothing(@TempDir Path results) {
		assertThat(smoke(results, 200).run(PYTHON)).isEmpty();
	}

	// the second experiment on the Java stack: java-mixed's stack, its own traffic
	@Test
	void run_javaHotRow_sendsItsOwnMixOnTheJavaStack(@TempDir Path results) {
		smoke(results, 200).run(Experiments.standard().get("java-hot-row"));

		assertThat(this.events).contains("--profile java-mixed up -d --build --wait",
				"k6 TARGET=http://api-java:8080 MIX=hot:1 RATE=20 WARMUP_S=0 DURATION_S=10",
				"--profile java-mixed down -v --remove-orphans");
	}

	@Test
	void run_cacheMiss_sendsItsLookupsOnTheCacheTtlStack(@TempDir Path results) {
		smoke(results, 200).run(Experiments.standard().get("cache-miss"));

		assertThat(this.events).contains("--profile cache-ttl up -d --build --wait",
				"k6 TARGET=http://api-ttl:8080 MIX=cached:1 RATE=20 WARMUP_S=0 DURATION_S=10");
	}

	// no latency, no results
	@Test
	void run_anExperiment_keepsNothing(@TempDir Path results) throws IOException {
		smoke(results, 200).run(PYTHON);

		try (Stream<Path> files = Files.walk(results)) {
			assertThat(files.filter(Files::isRegularFile)).isEmpty();
		}
	}

	@Test
	void runAndKeep_anExperiment_leavesItsStackRunning(@TempDir Path results) {
		smoke(results, 200).runAndKeep(PYTHON);

		assertThat(this.events.getLast()).isEqualTo("GET stats");
	}

	@Test
	void run_requestsTheServiceDidNotCount_logsTheStacksLogs(@TempDir Path results) {
		Logs logs = new Logs();

		smoke(results, 199, logs).run(PYTHON);

		assertThat(logs.text()).contains("the stacks' last 30 lines");
	}

	@Test
	void run_k6Failing_stillStopsTheStack(@TempDir Path results) {
		Smoke smoke = smoke(results, 200, new Logs(), " k6 run ");

		Throwable thrown = catchThrowable(() -> smoke.run(PYTHON));

		assertThat(thrown).isInstanceOf(IllegalStateException.class);
		assertThat(this.events.getLast()).isEqualTo("--profile python-mixed down -v --remove-orphans");
	}

	private Smoke smoke(Path results, int counted) {
		return smoke(results, counted, new Logs(), NOTHING_FAILS);
	}

	private Smoke smoke(Path results, int counted, Logs logs) {
		return smoke(results, counted, logs, NOTHING_FAILS);
	}

	// a smoke test on Docker and services that log each command and request, k6 writing
	// its summary, the service counting so many requests, and a command that fails
	private Smoke smoke(Path results, int counted, Logs logs, String failing) {
		Path summaries = results.resolve(".k6");
		FakeDocker docker = new FakeDocker((command) -> {
			this.events.add(event(command, summaries));
			if (command.contains(failing)) {
				return new ProcessResult(1, "", "k6 failed");
			}
			return FakeDocker.printing(command.contains(" logs ") ? "the stacks' last 30 lines" : "");
		});
		FakeServices services = new FakeServices((request) -> {
			this.events
				.add(request.substring(0, request.indexOf(' ') + 1) + request.substring(request.lastIndexOf('/') + 1));
			return request.endsWith("/stats") ? stats(counted) : JsonText.read("{}");
		});
		FakeTicker ticker = new FakeTicker(0);
		Compose compose = Compose.of(docker, results.resolve("compose.yaml"), Map.of(), logs.log());
		Instruments instruments = new Instruments(compose, new Cgroups(compose, Path.of("cgroups"), ticker), services,
				ticker);
		StackLifecycle stacks = new StackLifecycle(instruments,
				Cpusets.declaredIn(RepositoryLayout.current().compose()), Map.of(), logs.log());
		return new Smoke(stacks, summaries, logs.log());
	}

	// a compose command after the compose file, k6's by its traffic, k6 writing its
	// summary
	private static String event(String command, Path summaries) {
		String words = command.substring(command.indexOf(" -f ") + " -f ".length());
		String event = words.substring(words.indexOf(' ') + 1);
		if (!event.contains(" k6 run ")) {
			return event;
		}
		write(summaries);
		return "k6" + event.substring(event.indexOf(" -e TARGET"), event.indexOf(" -e KEYS")).replace(" -e", "");
	}

	// gunicorn's /stats after the smoke test
	private static JsonNode stats(int requests) {
		return JsonText.read("""
				{"requests": %d, "cpuMs": 12.5, "counters": {}, "workers": 10, "gc": {"collections": 1},
				 "timings": {"handler": {"count": %d}, "db_read": {"count": 181}, "db_write": {"count": 19}}}"""
			.formatted(requests, requests));
	}

	// k6's summary of the smoke test, where the harness reads it
	private static void write(Path summaries) {
		try {
			Files.createDirectories(summaries);
			Files.writeString(summaries.resolve("smoke.json"), SUMMARY);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
