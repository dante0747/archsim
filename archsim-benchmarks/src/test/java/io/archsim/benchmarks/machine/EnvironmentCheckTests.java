package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The environment check on pg-mixed's stack as a test plays it: k6 on CPUs 0, 1, 3, 6 and
 * 7, then with the service's physical core (2 and 3) left to the service alone.
 */
class EnvironmentCheckTests {

	@Test
	void run_aStack_writesAFileNamedAfterItsStart(@TempDir Path results) {
		Path written = new EnvironmentCheck(new FakeStack(results).tools()).run(results);

		assertThat(written).isEqualTo(results.resolve("envcheck").resolve("2026-11-13T12-34-56Z.json"));
	}

	@Test
	void run_aStack_recordsItsFieldsInRunPysOrder(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results);

		assertThat(check.propertyNames()).containsExactly("at", "environment", "workload", "how", "layouts");
	}

	@Test
	void run_aStack_measuresWithK6OnTheRunnersCpusThenOffTheServicesCore(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results);

		assertThat(check.get("layouts").propertyNames()).containsExactly("runner", "serviceCoreAlone");
		assertThat(check.at("/layouts/runner/k6Cpus").stringValue()).isEqualTo("0,1,3,6,7");
		assertThat(check.at("/layouts/serviceCoreAlone/k6Cpus").stringValue()).isEqualTo("0,1,6,7");
	}

	// the layout's own k6, not the stack's
	@Test
	void run_eachLayout_runsK6OnItsCpus(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		checked(stack, results);

		assertThat(k6Runs(stack).getFirst()).containsEntry("BENCH_CPUS_K6", "0,1,3,6,7");
		assertThat(k6Runs(stack).getLast()).containsEntry("BENCH_CPUS_K6", "0,1,6,7");
	}

	@Test
	void run_eachLayout_recordsHowItsCpusFallOnPhysicalCores(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results);

		assertThat(check.at("/layouts/runner/topology/sharedCores"))
			.isEqualTo(JsonText.read("[{\"containers\": [\"api\", \"k6\"], \"cores\": [[2, 3]]}]"));
		assertThat(check.at("/layouts/serviceCoreAlone/topology/sharedCores")).isEmpty();
	}

	@Test
	void run_eachLayout_stepsThroughTheRates(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results);

		assertThat(check.at("/layouts/runner/steps").valueStream().map((step) -> step.get("rateRps").intValue()))
			.containsExactly(50, 100, 200, 400, 800, 1600);
	}

	// PostgreSQL's 20 ms over k6's 20 s for 750 requests; one jiffy in a hundred stolen
	@Test
	void run_aStep_recordsTheCpuPerRequestAndTheSteal(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results);

		assertThat(check.at("/layouts/runner/steps/0")).isEqualTo(JsonText.read("""
				{"rateRps": 50, "k6": {"p50": 1.0, "p95": 9.0, "p99": 12.0, "successRate": 1.0},
				 "serviceCpuMsPerRequest": 0.5, "postgresCpuMsPerRequest": 0.0267, "nearTimerTick": [],
				 "vmSteal": 0.01}"""));
	}

	// Windows' timer interval, 15.625 ms: latencies piled near it come from the host
	@Test
	void run_aP95NearTheTimerTick_saysSo(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results, "16.0"), results);

		assertThat(check.at("/layouts/runner/steps/0/nearTimerTick")).isEqualTo(JsonText.read("[\"p95\"]"));
	}

	@Test
	void run_aStack_isStoppedAfterwards(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		checked(stack, results);

		assertThat(stack.docker().commands().getLast()).endsWith("--profile postgres down -v --remove-orphans");
	}

	@Test
	void run_eachStep_isLogged(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		checked(stack, results);

		assertThat(stack.logs().text().lines()).contains("[12:34:56] envcheck: 50 req/s (k6 on CPUs 0,1,3,6,7)",
				"[12:34:56] runner              50 req/s: service 0.5 ms, postgres 0.0267 ms per request, p95 9.0, "
						+ "steal 0.01");
	}

	// the variables each k6 run was given, in order
	private static List<Map<String, String>> k6Runs(FakeStack stack) {
		List<String> commands = stack.docker().commands();
		return IntStream.range(0, commands.size())
			.filter((index) -> commands.get(index).contains(" k6 run "))
			.mapToObj((index) -> stack.docker().environments().get(index))
			.toList();
	}

	private static JsonNode checked(FakeStack stack, Path results) {
		return ResultFiles.read(new EnvironmentCheck(stack.tools()).run(results)).orElseThrow();
	}

}
