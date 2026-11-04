package io.archsim.engine.technology;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.Tier;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.NodeSpec;

import static org.assertj.core.api.Assertions.assertThat;

/** Each part's technology and tier; the cases are what the Python engine 0.16.0 gives. */
class TechnologyTiersTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TechnologyTiers tiers = new TechnologyTiers(StandardComponents.registry());

	@Test
	void of_partsWithTechnologies_giveTheirTechnologyAndTier() {
		List<PartTechnology> found = this.tiers.of(nodes("{\"id\": \"c\", \"type\": \"cache\", \"label\": \"\"}",
				"{\"id\": \"db\", \"type\": \"database\", \"label\": \"Orders\", "
						+ "\"config\": {\"technology\": \"cassandra\"}}"));

		assertThat(found).containsExactly(new PartTechnology("c", "c", "cache", "redis", Tier.CORE),
				new PartTechnology("db", "Orders", "database", "cassandra", Tier.EXPERIMENTAL));
	}

	@Test
	void of_partsWithoutTechnologyProfiles_areLeftOut() {
		assertThat(this.tiers.of(nodes("{\"id\": \"clients\", \"type\": \"traffic_source\"}",
				"{\"id\": \"web\", \"type\": \"web_client\"}")))
			.isEmpty();
	}

	@Test
	void of_unknownTypesAndConfigsThatAreNotValid_areLeftToValidation() {
		assertThat(this.tiers.of(nodes("{\"id\": \"x\", \"type\": \"mystery\"}",
				"{\"id\": \"bad\", \"type\": \"cache\", \"config\": {\"replicas\": \"lots\"}}")))
			.isEmpty();
	}

	@Test
	void of_aPart_isWrittenAsThePythonEngineWritesIt() {
		assertThat(RecordCodec.write(new PartTechnology("db", "Orders", "database", "cassandra", Tier.EXPERIMENTAL)))
			.isEqualTo(JSON.readTree("{\"id\": \"db\", \"label\": \"Orders\", \"type\": \"database\", "
					+ "\"technology\": \"cassandra\", \"tier\": \"experimental\"}"));
	}

	private static List<NodeSpec> nodes(String... nodes) {
		List<NodeSpec> read = new ArrayList<>();
		for (String node : nodes) {
			read.add(RecordCodec.read(JSON.readTree(node), NodeSpec.class));
		}
		return read;
	}

}
