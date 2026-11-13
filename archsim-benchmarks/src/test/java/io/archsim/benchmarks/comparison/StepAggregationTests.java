package io.archsim.benchmarks.comparison;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;

import static org.assertj.core.api.Assertions.assertThat;

class StepAggregationTests {

	@Test
	void aggregate_mostRepeatsFailed_leavesTheStepWithoutLatency() {
		ObjectNode step = StepAggregation
			.aggregate(List.of(Repeats.repeat(332.0), Repeats.repeat(null), Repeats.repeat(null)));

		assertThat(List.of(step.get("p50"), step.get("p95"), step.get("p99"))).allMatch(JsonNode::isNull);
		assertThat(step.get("successRate").doubleValue()).isZero();
		assertThat(step.get("failedRepeats").intValue()).isEqualTo(2);
		assertThat(step.get("repeats").intValue()).isEqualTo(3);
		assertThat(step.get("unstable").booleanValue()).isTrue();
	}

	@Test
	void aggregate_oneFailedRepeat_takesTheSlowerOfTheOthers() {
		ObjectNode step = StepAggregation
			.aggregate(List.of(Repeats.repeat(2.0), Repeats.repeat(null), Repeats.repeat(3.0)));

		assertThat(step.get("p95").doubleValue()).isEqualTo(3.0);
		assertThat(step.get("successRate").doubleValue()).isEqualTo(1.0);
		assertThat(step.get("failedRepeats").intValue()).isEqualTo(1);
		// a failed repeat is infinitely slower than the others
		assertThat(step.get("unstable").booleanValue()).isTrue();
	}

	// run 4 recorded failed repeats with k6's zeros; they must not read as 0 ms
	@Test
	void aggregate_repeatsRecordedAsZeroMilliseconds_countAsFailed() {
		ObjectNode step = StepAggregation.aggregate(List.of(Repeats.repeat(10.0), Repeats.zeros(), Repeats.zeros()));

		assertThat(step.get("p95").isNull()).isTrue();
		assertThat(step.get("failedRepeats").intValue()).isEqualTo(2);
	}

	@Test
	void aggregate_everyRepeatFailed_isNotUnstable() {
		ObjectNode step = StepAggregation
			.aggregate(List.of(Repeats.repeat(null), Repeats.repeat(null), Repeats.repeat(null)));

		assertThat(step.get("p95").isNull()).isTrue();
		assertThat(step.get("unstable").booleanValue()).isFalse();
	}

	// more than 2x apart, but all under the objective
	@Test
	void aggregate_repeatsUnderTheObjective_isStable() {
		ObjectNode step = StepAggregation
			.aggregate(List.of(Repeats.repeat(2.0), Repeats.repeat(2.2), Repeats.repeat(4.5)));

		assertThat(step.get("p95").doubleValue()).isEqualTo(2.2);
		assertThat(step.get("unstable").booleanValue()).isFalse();
		assertThat(step.get("failedRepeats").intValue()).isZero();
	}

	@Test
	void aggregate_sampledRepeats_takesTheMedianOfEachContainersResources() {
		List<ObjectNode> runs = List.of(sampled(0.5, 90), sampled(0.7, 70), sampled(0.6, 80));

		ObjectNode step = StepAggregation.aggregate(runs);

		assertThat(step.get("resources")).isEqualTo(JsonText.read("""
				{"method": "window", "repeats": 3, "containers": {
				  "api": {"cores": 0.6, "workingSetMiB": 80},
				  "redis": {"cores": 0.06}}}"""));
	}

	@Test
	void aggregate_withoutResources_recordsNone() {
		ObjectNode step = StepAggregation.aggregate(List.of(Repeats.repeat(2.0)));

		assertThat(step.has("resources")).isFalse();
	}

	private static ObjectNode sampled(double cores, int memoryMib) {
		ObjectNode containers = JsonText.object("{}");
		containers.putObject("api").put("cores", cores).put("workingSetMiB", memoryMib);
		containers.putObject("redis").put("cores", cores / 10);
		ObjectNode repeat = Repeats.repeat(2.0);
		repeat.putObject("resources").put("method", "window").set("containers", containers);
		return repeat;
	}

}
