package io.archsim.engine.sizing;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Kafka broker's sizes are what ArchSim's Kafka experiment measured (ADR-0085): a
 * least-squares fit of the broker's busy cores to the messages it handled a second, over
 * the steps it kept up with, and the largest working set over them.
 */
class KafkaMeasurementsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final double MS_PER_SECOND = 1000;

	private static final double MIB_PER_GIB = 1024;

	private final List<JsonNode> keptUp = keptUp();

	@Test
	void fixedCores_theFitsIntercept_isTheSizersToTwoDecimals() {
		assertThat(round(fit().intercept(), 2)).isEqualTo(Sizing.KAFKA_FIXED_CORES);
	}

	@Test
	void cpuPerMessage_theFitsSlope_isTheSizersToThreeDecimals() {
		assertThat(round(fit().slope() * MS_PER_SECOND, 3)).isEqualTo(Sizing.KAFKA_CPU_MS_PER_MESSAGE);
	}

	@Test
	void workingSet_theLargestOverTheSteps_isTheSizersToTwoDecimals() {
		double largest = this.keptUp.stream()
			.mapToDouble((step) -> broker(step).path("workingSetMiB").doubleValue())
			.max()
			.orElseThrow();

		assertThat(round(largest / MIB_PER_GIB, 2)).isEqualTo(Sizing.KAFKA_WORKING_SET_GIB);
	}

	// busy cores over messages a second
	private Line fit() {
		double meanX = this.keptUp.stream().mapToDouble(KafkaMeasurementsTests::rate).average().orElseThrow();
		double meanY = this.keptUp.stream().mapToDouble(KafkaMeasurementsTests::cores).average().orElseThrow();
		double sxx = this.keptUp.stream().mapToDouble((step) -> Math.pow(rate(step) - meanX, 2)).sum();
		double sxy = this.keptUp.stream().mapToDouble((step) -> (rate(step) - meanX) * (cores(step) - meanY)).sum();
		double slope = sxy / sxx;
		return new Line(meanY - slope * meanX, slope);
	}

	private static double rate(JsonNode step) {
		return step.path("queue").path("consumedRps").doubleValue();
	}

	private static double cores(JsonNode step) {
		return broker(step).path("cores").doubleValue();
	}

	private static JsonNode broker(JsonNode step) {
		return step.path("resources").path("containers").path("kafka");
	}

	private static double round(double value, int decimals) {
		double scale = Math.pow(10, decimals);
		return Math.round(value * scale) / scale;
	}

	// the steps the broker kept up with: nothing left in its backlog at the end
	private static List<JsonNode> keptUp() {
		try {
			JsonNode measured = JSON
				.readTree(Files.readString(Repository.root().resolve("benchmarks/results/kafka/measured.json")));
			return measured.path("steps")
				.valueStream()
				.filter((step) -> step.path("queue").path("backlogAtEnd").asLong(-1) == 0)
				.toList();
		}
		catch (IOException ex) {
			throw new IllegalStateException("cannot read the Kafka experiment's results", ex);
		}
	}

	/**
	 * A straight line fitted to the steps.
	 *
	 * @param intercept its value at no messages, in cores
	 * @param slope the cores each message a second adds
	 */
	private record Line(double intercept, double slope) {

	}

}
