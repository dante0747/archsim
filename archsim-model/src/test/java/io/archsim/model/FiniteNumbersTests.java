package io.archsim.model;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.BindingError;
import io.archsim.model.codec.BindingException;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.issue.Issue;
import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.tuple;

/**
 * No number of a design is NaN or an infinity: every float of every record the codec
 * reads for a design, its parts' settings included, refuses each way of writing one
 * ({@link FloatSlots} finds them, so none is listed by hand).
 */
class FiniteNumbersTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	private static final List<JsonNode> NOT_FINITE = List.of(NODES.stringNode("nan"), NODES.stringNode("inf"),
			NODES.stringNode("-Infinity"), NODES.stringNode("1e999"), NODES.numberNode(Double.NaN),
			NODES.numberNode(Double.POSITIVE_INFINITY), NODES.numberNode(Double.NEGATIVE_INFINITY));

	static Stream<Arguments> floatsNotFinite() {
		return FloatSlots.ofADesign()
			.stream()
			.flatMap((slot) -> NOT_FINITE.stream().map((value) -> Arguments.of(slot, value)));
	}

	@ParameterizedTest(name = "{0} as {1}")
	@MethodSource("floatsNotFinite")
	void read_aFloatThatIsNotFinite_isRefused(FloatSlot slot, JsonNode value) {
		BindingException refused = catchThrowableOfType(BindingException.class,
				() -> RecordCodec.read(slot.inputWith(value), slot.holder()));

		assertThat(refused.errors())
			.contains(new BindingError("finite_number", slot.location(), "Input should be a finite number"));
	}

	@Test
	void floatSlots_ofADesign_reachItsWorkloadItsCurvesItsLayoutAndItsPartsSettings() {
		assertThat(FloatSlots.ofADesign()).extracting(FloatSlot::toString)
			.contains("Workload.rps", "TracePoint[0]", "TracePoint[1]", "Quantile[0]", "Quantile[1]", "Position.x",
					"ServiceConfig.scaleIntervalS", "Constant.value");
	}

	@Test
	void load_aTraceTimeThatIsNotANumber_isRefusedWhereItIs() {
		ObjectNode design = starter();
		ArrayNode points = ((ObjectNode) design.get("workload")).putObject("pattern")
			.put("kind", "trace")
			.putArray("points");
		points.addArray().add(0).add(50);
		points.addArray().add("nan").add(50);

		DocumentInvalidException refused = catchThrowableOfType(DocumentInvalidException.class,
				() -> DesignDocuments.load(design));

		assertThat(refused.issues()).extracting(Issue::path, Issue::message)
			.containsExactly(tuple("workload.pattern.trace.points.1.0",
					"workload.pattern.trace.points.1.0: Input should be a finite number "
							+ "(at workload.pattern.trace.points.1.0)"));
	}

	private static ObjectNode starter() {
		return (ObjectNode) JSON.readTree(Repository.examples().resolve("cache-aside-starter.json").toFile());
	}

}
