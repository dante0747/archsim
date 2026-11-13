package io.archsim.benchmarks.machine;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.results.ResultFiles;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The contention check on pg-mixed's stack as a test plays it: Windows' counters sampled
 * once a second throughout, k6's CPU watched on the test's own thread, each probe
 * printing 20,000 units in 600 ms of CPU over 12 s.
 */
class ContentionCheckTests {

	@Test
	void run_aCheck_writesAFileNamedAfterItsStart(@TempDir Path results) {
		Path written = check(new FakeStack(results)).run(results, ContentionCheck.Parts.SERVICE_ONLY);

		assertThat(written).isEqualTo(results.resolve("envcheck").resolve("2026-11-13T12-34-56Z-contention.json"));
	}

	@Test
	void run_aCheck_recordsItsFieldsInRunPysOrder(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results, ContentionCheck.Parts.SERVICE_ONLY);

		assertThat(check.propertyNames()).containsExactly("at", "environment", "topology", "how", "probeRounds",
				"serviceAtRest", "service");
	}

	@Test
	void run_aCheck_recordsTheVmsTopology(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results, ContentionCheck.Parts.SERVICE_ONLY);

		assertThat(check.at("/topology/siblings/3")).isEqualTo(JsonText.read("[2, 3]"));
	}

	// the stack up and idle for 15 s, as Windows saw it
	@Test
	void run_theServicePart_measuresTheMachineAtRest(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results, ContentionCheck.Parts.SERVICE_ONLY);

		assertThat(check.at("/serviceAtRest/busyLogicalProcessors").doubleValue()).isEqualTo(0.4);
	}

	@Test
	void run_theServiceOnly_stepsThroughTheQuickLadderAsBenchmarked(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results, ContentionCheck.Parts.SERVICE_ONLY);

		assertThat(steps(check)).containsExactly("as benchmarked@1250", "as benchmarked@2500", "as benchmarked@3700");
		assertThat(check.get("probeRounds")).isEmpty();
	}

	// the ladder, then at half the event loop's core with background work
	@Test
	void run_both_stepsThroughTheLadderThenTheConditions(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results, ContentionCheck.Parts.BOTH);

		assertThat(steps(check)).containsExactly("as benchmarked@1250", "as benchmarked@2500", "as benchmarked@3700",
				"as benchmarked@4400", "as benchmarked@5000", "service's sibling busy@2500", "k6's idle CPU taken@2500",
				"PostgreSQL's idle CPU taken@2500", "every idle CPU taken@2500");
	}

	// k6's 14 ms of CPU over the 14 s it was watched;
	// the service's and PostgreSQL's 36 ms over the run's 36 s
	@Test
	void run_aServiceStep_recordsEachContainersCpu(@TempDir Path results) {
		JsonNode step = checked(new FakeStack(results), results, ContentionCheck.Parts.SERVICE_ONLY).at("/service/0");

		assertThat(step.get("k6Cores").doubleValue()).isEqualTo(0.001);
		assertThat(step.get("serviceCores").doubleValue()).isEqualTo(0.001);
		assertThat(step.get("postgresCpuMsPerRequest").doubleValue()).isEqualTo(0.048);
	}

	@Test
	void run_aServiceStep_recordsItsFieldsInRunPysOrder(@TempDir Path results) {
		JsonNode step = checked(new FakeStack(results), results, ContentionCheck.Parts.SERVICE_ONLY).at("/service/0");

		assertThat(step.propertyNames()).containsExactly("condition", "rateRps", "burners", "k6",
				"serviceCpuMsPerRequest", "serviceCores", "postgresCpuMsPerRequest", "postgresCores", "k6Cores",
				"host");
	}

	// k6's CPUs at nice 19, so the burners take only what k6 leaves idle
	@Test
	void run_aConditionWithBurners_startsThemAtTheLowestPriority(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		JsonNode step = checked(stack, results, ContentionCheck.Parts.BOTH).at("/service/6");

		assertThat(step.get("burners")).isEqualTo(
				JsonText.read("{\"0\": \"nice 19\", \"1\": \"nice 19\", \"6\": \"nice 19\", \"7\": \"nice 19\"}"));
		assertThat(stack.docker().commands()).anyMatch((command) -> command
			.contains("--name archsim-bench-probe-svc-k6-s-idle-cpu-taken-6 --cpuset-cpus 6 -e PROBE_S=50 -e DUTY=1")
				&& command
					.endsWith("--entrypoint nice node:24-alpine -n 19 node -e (0,eval)(atob(process.env.PROBE_CODE))"));
	}

	// each round runs every condition, the second in reverse order, so drift shows
	@Test
	void run_theProbe_runsEveryConditionTwiceTheSecondTimeReversed(@TempDir Path results) {
		JsonNode check = checked(new FakeStack(results), results, ContentionCheck.Parts.PROBE_ONLY);

		assertThat(check.get("probeRounds").valueStream().map(JsonNode::size)).containsExactly(12, 12);
		assertThat(check.at("/probeRounds/1/0/condition").stringValue()).isEqualTo("own duty 50%, 3 other cores busy");
	}

	@Test
	void run_theProbeOnly_measuresNoService(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		JsonNode check = checked(stack, results, ContentionCheck.Parts.PROBE_ONLY);

		assertThat(check.get("serviceAtRest").isNull()).isTrue();
		assertThat(stack.docker().commands()).noneMatch((command) -> command.contains(" up -d "));
	}

	// 20,000 units in 600 ms of CPU over 12 s
	@Test
	void run_aProbeCondition_recordsTheProbesCostAndSpeed(@TempDir Path results) {
		JsonNode condition = checked(new FakeStack(results), results, ContentionCheck.Parts.PROBE_ONLY)
			.at("/probeRounds/0/1");

		assertThat(condition.get("probe")).isEqualTo(JsonText.read("""
				{"units": 20000, "lateMs": 3, "cpuUsPerUnit": 30.0, "unitsPerWallMs": 1.667, "busy": 0.05}"""));
		assertThat(condition.get("burnerResults").propertyNames()).containsExactly("3");
	}

	// every container of a condition over the same window, after the lead
	@Test
	void run_aProbeCondition_startsEveryContainerAtOneMoment(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		checked(stack, results, ContentionCheck.Parts.PROBE_ONLY);

		assertThat(stack.docker()
			.commands()
			.stream()
			.filter((command) -> command.contains("--name archsim-bench-probe-every-cpu-busy-"))
			.map((command) -> command.replaceAll(".* START_AT=(\\d+) .*", "$1"))
			.distinct()).hasSize(2);
	}

	@Test
	void run_aCheck_clearsItsProbesAfterwards(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		checked(stack, results, ContentionCheck.Parts.PROBE_ONLY);

		assertThat(stack.docker().commands().getLast()).isEqualTo("docker ps -aq --filter name=archsim-bench-probe-");
	}

	@Test
	void run_aServiceStep_isLogged(@TempDir Path results) {
		FakeStack stack = new FakeStack(results);

		checked(stack, results, ContentionCheck.Parts.SERVICE_ONLY);

		assertThat(stack.logs().text().lines()).contains("[12:34:56] contention: machine at rest 0.4 busy LPs",
				"[12:34:56] contention service  1250 req/s as benchmarked                 service 0.5 ms, "
						+ "postgres 0.048 ms, p95 9.0, k6 0.001 cores, MHz 3000.0, busy LPs 0.4");
	}

	// the step's condition and rate, in order
	private static List<String> steps(JsonNode check) {
		return check.get("service")
			.valueStream()
			.map((step) -> step.get("condition").stringValue() + "@" + step.get("rateRps").intValue())
			.toList();
	}

	private static ContentionCheck check(FakeStack stack) {
		return new ContentionCheck(stack.tools(), () -> HostCounters.of(stack.samples()), Runnable::run);
	}

	private static JsonNode checked(FakeStack stack, Path results, ContentionCheck.Parts parts) {
		return ResultFiles.read(check(stack).run(results, parts)).orElseThrow();
	}

}
