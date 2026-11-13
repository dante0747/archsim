package io.archsim.benchmarks.campaign;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.Logs;
import io.archsim.benchmarks.docker.Cgroups;
import io.archsim.benchmarks.docker.Compose;
import io.archsim.benchmarks.docker.FakeDocker;
import io.archsim.benchmarks.docker.FakeServices;
import io.archsim.benchmarks.docker.FakeTicker;
import io.archsim.benchmarks.docker.Instruments;
import io.archsim.benchmarks.docker.ProcessResult;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.load.K6;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A step measured three times on a stack a test plays: k6's summary of 1,000 requests a
 * repeat, each container's CPU rising a millisecond a second, the stack drained between
 * repeats. The sampler reads on the caller's thread.
 */
class MeasurementTests {

	private static final String CONSUMER_STATS = "GET http://127.0.0.1:18081/stats";

	private static final String CONSUMER_RESET = "POST http://127.0.0.1:18081/reset";

	private static final String KAFKA_COUNTERS = "{\"produced:measure\": 500, \"produced:warm\": 100}";

	private final List<String> requests = Collections.synchronizedList(new ArrayList<>());

	private final AtomicInteger consumerAsks = new AtomicInteger();

	@Test
	void step_threeRepeats_aggregatesThem(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", "{}"), "pg-mixed");

		assertThat(step.get("runs").size()).isEqualTo(Measurement.REPEATS);
		assertThat(step.get("p95").doubleValue()).isEqualTo(9.0);
	}

	// the load generator failed, not the system
	@Test
	void step_aRepeatInWhichK6SentNothing_runsItAgain(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(2, "0", "{}"), "pg-mixed");

		assertThat(step.get("discardedRuns").size()).isOne();
		assertThat(step.get("runs").size()).isEqualTo(Measurement.REPEATS);
	}

	// measured.json's bytes depend on it
	@Test
	void step_aRepeat_recordsItsFieldsInRunPysOrder(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", "{}"), "pg-mixed");

		assertThat(step.at("/runs/0").propertyNames()).containsExactly("offeredRps", "p50", "p95", "p99", "mean",
				"sent", "dropped", "failed", "succeeded", "successRate", "throughputRps", "serviceCpuMsPerRequest",
				"containerCpuShare", "resources", "drain", "at");
	}

	// a millisecond of CPU a second, over the whole k6 command
	@Test
	void step_aRepeat_recordsEachContainersShareOfACore(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", "{}"), "pg-mixed");

		assertThat(step.at("/runs/0/containerCpuShare"))
			.isEqualTo(JsonText.read("{\"api\": 0.001, \"postgres\": 0.001}"));
	}

	@Test
	void step_aDrainedStack_recordsItHealthyAndIdle(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", "{}"), "pg-mixed");

		assertThat(step.at("/runs/0/drain"))
			.isEqualTo(JsonText.read("{\"seconds\": 0.0, \"healthy\": true, \"databaseIdle\": true}"));
	}

	@Test
	void step_aCache_recordsItsHitRatio(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", "{\"cache_hit:measure\": 3, \"cache_miss:measure\": 1}"),
				"cache-aside");

		assertThat(step.at("/runs/0/cacheHitRatio").doubleValue()).isEqualTo(0.75);
	}

	@Test
	void step_aCacheNothingWasLookedUpIn_hasNoHitRatio(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", "{}"), "cache-aside");

		assertThat(step.at("/runs/0/cacheHitRatio").isNull()).isTrue();
	}

	// the step's 500 messages and the warm-up's 100, all consumed over the step's 25 s
	@Test
	void step_kafka_recordsTheQueue(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", KAFKA_COUNTERS), "kafka");

		// the counts are longs, so compared as written
		assertThat(step.at("/runs/0/queue").toString()).isEqualTo("""
				{"produced":600,"consumedDuringStep":600,"consumedRps":24.0,"backlogAtEnd":0,\
				"p95MessageAgeMs":8.0,"meanMessageAgeMs":3.0}""");
	}

	// the consumer's statistics reset for the next repeat, and nothing else drained
	@Test
	void step_kafka_resetsTheConsumerRatherThanDrain(@TempDir Path folder) {
		ObjectNode step = measured(folder, new Stack(3, "0", "{\"produced:measure\": 500}"), "kafka");

		assertThat(this.requests).contains(CONSUMER_RESET);
		assertThat(step.at("/runs/0").has("drain")).isFalse();
	}

	// each repeat asks before the step, after it (100 of 600), then every 2 s until all
	// 600 are consumed: at 200, 300, 400, 500 and 600
	@Test
	void step_kafkaBehindWhenTheStepEnds_waitsForTheBacklog(@TempDir Path folder) {
		measured(folder, new Stack(3, "0", KAFKA_COUNTERS, 100), "kafka");

		assertThat(consumerAsked()).isEqualTo(Measurement.REPEATS * 7);
	}

	// each repeat asks before the step, after it, then every 2 s for 120 s
	@Test
	void step_kafkaThatNeverCatchesUp_waitsTwoMinutesAtMost(@TempDir Path folder) {
		measured(folder, new Stack(3, "0", KAFKA_COUNTERS, 0), "kafka");

		assertThat(consumerAsked()).isEqualTo(Measurement.REPEATS * (2 + 60));
	}

	@Test
	void drain_aDatabaseStillBusy_waitsAMinuteAndSaysSo(@TempDir Path folder) {
		Logs logs = new Logs();

		JsonNode drained = measurement(folder, new Stack(3, "2", "{}"), logs).drain();

		assertThat(drained).isEqualTo(JsonText.read("{\"seconds\": 60.0, \"healthy\": true, \"databaseIdle\": false}"));
		assertThat(logs.text().lines())
			.contains("[12:34:56]   not drained after 60 s (service healthy: True, database idle: False)");
	}

	private ObjectNode measured(Path folder, Stack stack, String experiment) {
		return measurement(folder, stack, new Logs()).step(Experiments.standard().get(experiment), 50);
	}

	// a stack as the test sets it up
	private Measurement measurement(Path folder, Stack stack, Logs logs) {
		Path summaries = folder.resolve(".k6");
		FakeTicker ticker = new FakeTicker(0);
		FakeDocker docker = new FakeDocker(
				new Played(stack, ticker, summaries, new AtomicInteger(stack.k6RunsBefore()))::answer);
		Compose compose = Compose.of(docker, folder.resolve("compose.yaml"), Map.of(), logs.log());
		Instruments instruments = new Instruments(compose, new Cgroups(compose, folder, ticker),
				new FakeServices((request) -> service(request, stack)), ticker);
		return new Measurement(instruments, new K6(compose, summaries), Runnable::run, logs.log());
	}

	// the service's statistics, its counters as set; Kafka's consumer at 0 messages when
	// reset, catching up so many more each time it is asked, up to the 600 produced
	private JsonNode service(String request, Stack stack) {
		this.requests.add(request);
		if (request.equals(CONSUMER_RESET)) {
			this.consumerAsks.set(0);
		}
		if (request.equals(CONSUMER_STATS)) {
			long consumed = Math.min(600, (long) this.consumerAsks.getAndIncrement() * stack.consumedPerAsk());
			return JsonText.read("{\"consumed\": " + consumed
					+ ", \"timings\": {\"end_to_end:measure\": {\"p95\": 8.0, \"mean\": 3.0}}}");
		}
		return JsonText.read("{\"requests\": 1, \"cpuMsPerRequest\": 0.3, \"counters\": " + stack.counters() + "}");
	}

	private long consumerAsked() {
		return this.requests.stream().filter(CONSUMER_STATS::equals).count();
	}

	// k6's summary of a repeat that sent so many requests, all of them succeeding
	private static String summary(int sent) {
		return """
				{"http_req_duration{scenario:measure,expected_response:true}": {"values": {"med": 3.0, "p(95)": 9.0,
				 "p(99)": 12.0, "avg": 4.0, "count": %d}}, "http_reqs{scenario:measure}": {"values": {"count": %d}}}"""
			.formatted(sent, sent);
	}

	private static void write(Path summaries, String name, String text) {
		try {
			Files.createDirectories(summaries);
			Files.writeString(summaries.resolve(name + ".json"), text);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/**
	 * A stack as a test sets it up.
	 *
	 * @param k6RunsBefore the k6 runs counted from: k6 sends nothing until they reach 3
	 * @param activeQueries the queries PostgreSQL has active
	 * @param counters the service's counters
	 * @param consumedPerAsk how many more messages Kafka's consumer has consumed each
	 * time it is asked
	 */
	private record Stack(int k6RunsBefore, String activeQueries, String counters, int consumedPerAsk) {

		// a consumer that has caught up by the time the step ends
		Stack(int k6RunsBefore, String activeQueries, String counters) {
			this(k6RunsBefore, activeQueries, counters, 600);
		}

	}

	/**
	 * The stack a test plays, as Docker answers it.
	 *
	 * @param stack the stack as the test set it up
	 * @param ticker the clock the containers' CPU counters follow
	 * @param summaries where k6 writes its summaries
	 * @param k6Runs the k6 runs so far
	 */
	private record Played(Stack stack, FakeTicker ticker, Path summaries, AtomicInteger k6Runs) {

		// k6's summary, the containers' counters, PostgreSQL's active queries
		ProcessResult answer(String command) {
			if (command.contains(" ps -q ")) {
				return FakeDocker.printing("id-" + command.substring(command.lastIndexOf(' ') + 1));
			}
			if (command.contains(" k6 run ")) {
				int sent = (this.k6Runs.getAndIncrement() >= 3) ? 1000 : 0;
				write(this.summaries,
						command.substring(command.indexOf("OUT=") + "OUT=".length(), command.indexOf(" k6 run")),
						summary(sent));
				return FakeDocker.printing("");
			}
			if (command.contains("pg_stat_activity")) {
				return new ProcessResult(0, this.stack.activeQueries() + "\n", "");
			}
			return FakeDocker.printing(command.startsWith("docker exec ")
					? "usage_usec " + (long) (this.ticker.now() * 1000) + "\nanon 41943040\n44040192\n" : "");
		}

	}

}
