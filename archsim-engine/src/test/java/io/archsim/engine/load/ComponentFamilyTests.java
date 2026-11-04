package io.archsim.engine.load;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;

/** The family a component type belongs to. */
class ComponentFamilyTests {

	@ParameterizedTest(name = "{0} is {1}")
	@CsvSource({ "traffic_source, TRAFFIC", "web_client, TRAFFIC", "load_balancer, NETWORK", "service, COMPUTE",
			"kubernetes_cluster, COMPUTE", "cache, CACHE", "database, DATABASE", "message_queue, MESSAGING",
			"external_api, EXTERNAL" })
	void of_aStandardType_isItsFamily(String type, ComponentFamily family) {
		assertThat(ComponentFamily.of(type, StandardComponents.registry())).isEqualTo(family);
	}

	@Test
	void of_aTypeTheRegistryDoesNotKnow_isOther() {
		assertThat(ComponentFamily.of("quantum_computer", StandardComponents.registry()))
			.isEqualTo(ComponentFamily.OTHER);
	}

}
