package io.archsim.engine.compare;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * An input difference keeps its own copies of the values: changing the JSON it was made
 * from, or a value it gave out, does not change it.
 */
class InputDifferenceTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void values_changedAfterTheDifferenceIsMade_stayAsTheyWere() {
		ArrayNode points = NODES.arrayNode().add(1).add(2);
		InputDifference difference = new InputDifference("workload.points", "Workload: points", points,
				NODES.nullNode());

		points.add(3);

		assertThat(difference.a()).isEqualTo(NODES.arrayNode().add(1).add(2));
	}

	@Test
	void values_changedByTheirReader_stayAsTheyWere() {
		InputDifference difference = new InputDifference("workload.points", "Workload: points",
				NODES.arrayNode().add(1), NODES.nullNode());

		((ArrayNode) difference.a()).add(2);

		assertThat(difference.a()).isEqualTo(NODES.arrayNode().add(1));
	}

}
