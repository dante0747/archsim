package io.archsim.engine.imports;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * CPU and memory as Kubernetes and Compose write them.
 */
class QuantitiesTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void cpu_millicores_areCores() {
		assertThat(Quantities.cpu(NODES.stringNode("500m"))).hasValue(0.5);
	}

	@Test
	void cpu_textThatIsNoNumber_isNone() {
		assertThat(Quantities.cpu(NODES.stringNode("lots"))).isEmpty();
	}

	@Test
	void memoryGib_dockersMegabytes_areBinary() {
		assertThat(Quantities.memoryGib(NODES.stringNode("1536M"))).hasValue(1.5);
	}

	@Test
	void memoryGib_kubernetesGibibytes_areGib() {
		assertThat(Quantities.memoryGib(NODES.stringNode("4Gi"))).hasValue(4.0);
	}

	@Test
	void memoryGib_unknownUnit_isNone() {
		assertThat(Quantities.memoryGib(NODES.stringNode("4 parsecs"))).isEmpty();
	}

	@Test
	void memoryGib_digitsThatAreNoNumber_areRefusedInPythonsWords() {
		assertThatExceptionOfType(ImportRefusedException.class)
			.isThrownBy(() -> Quantities.memoryGib(NODES.stringNode("1.2.3Gi")))
			.withMessage("could not convert string to float: '1.2.3'");
	}

}
