package io.archsim.benchmarks.inputs;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.benchmarks.JsonText;
import io.archsim.benchmarks.RepositoryLayout;
import io.archsim.benchmarks.designs.Designs;
import io.archsim.benchmarks.experiments.Experiments;
import io.archsim.benchmarks.results.ResultFiles;
import io.archsim.model.text.PythonJson;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The node builders keep the original four experiments' designs exactly as they were
 * predicted, but for the Kafka consumer W23 remodelled: one kafkajs consumer that polls
 * in fetch cycles, three partitions at a time, instead of three consumers taking one
 * message each (their calibrations predate the inputs it needs).
 */
class TunedOnDesignsTests {

	private static final List<String> W23_CONSUMER = List.of("consumers", "consumerConcurrency", "fetchOverheadMs");

	@ParameterizedTest
	@ValueSource(strings = { "pg-mixed", "pg-hot-row", "cache-aside" })
	void design_aTunedOnExperimentsCalibration_isTheDesignItsPredictionsUsed(String experiment) {
		JsonNode predictions = latest(experiment, "predictions.json");

		ObjectNode design = Designs.design(Experiments.standard().get(experiment),
				latest(experiment, "calibration.json").get("inputs"), predictions.at("/knee/kneeRps"),
				Designs.Window.STEP);

		assertThat(PythonJson.writeIndented(design, 2))
			.isEqualTo(PythonJson.writeIndented(predictions.get("design"), 2));
	}

	@Test
	void design_kafkasCalibration_isTheDesignItsPredictionsUsedButForTheConsumer() {
		JsonNode predictions = latest("kafka", "predictions.json");

		ObjectNode design = Designs.design(Experiments.standard().get("kafka"), kafkaInputs(),
				predictions.at("/knee/kneeRps"), Designs.Window.STEP);

		assertThat(PythonJson.writeIndented(withoutConsumer(design), 2))
			.isEqualTo(PythonJson.writeIndented(withoutConsumer(predictions.get("design")), 2));
	}

	@Test
	void design_kafkasCalibration_hasOneConsumerOnThreePartitionsAtATime() {
		JsonNode predictions = latest("kafka", "predictions.json");

		ObjectNode design = Designs.design(Experiments.standard().get("kafka"), kafkaInputs(),
				predictions.at("/knee/kneeRps"), Designs.Window.STEP);

		JsonNode queue = design.at("/architecture/nodes")
			.valueStream()
			.filter((node) -> node.get("id").stringValue().equals("kafka"))
			.findFirst()
			.orElseThrow()
			.get("config");
		assertThat(List.of(queue.get("consumers").intValue(), queue.get("consumerConcurrency").intValue(),
				queue.at("/fetchOverheadMs/median").doubleValue()))
			.containsExactly(1, QueueInputs.CONSUMERS, 0.93);
	}

	// the latest calibration's inputs and the consumer's: the stack's concurrency,
	// and the W23 trial's fetch overhead, which the calibration did not record
	private static ObjectNode kafkaInputs() {
		ObjectNode inputs = ((ObjectNode) latest("kafka", "calibration.json").get("inputs")).deepCopy();
		inputs.putObject("consumerConcurrency").put("value", QueueInputs.CONSUMERS);
		inputs.set("fetchOverheadMs", QueueInputs.fetchOverhead(JsonText.read("{\"timings\": {}}")));
		return inputs;
	}

	private static JsonNode withoutConsumer(JsonNode design) {
		JsonNode copy = design.deepCopy();
		copy.at("/architecture/nodes")
			.valueStream()
			.filter((node) -> node.get("type").stringValue().equals("message_queue"))
			.forEach((node) -> W23_CONSUMER.forEach(((ObjectNode) node.get("config"))::remove));
		return copy;
	}

	private static JsonNode latest(String experiment, String file) {
		return ResultFiles.read(RepositoryLayout.current().results().resolve(experiment).resolve(file)).orElseThrow();
	}

}
