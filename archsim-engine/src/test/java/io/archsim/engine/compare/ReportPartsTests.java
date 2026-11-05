package io.archsim.engine.compare;

import java.util.Objects;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Report parts keep their own copies: changing the report they were copied from, or a
 * part they gave out, does not change them; a part a report does not have stays none.
 */
class ReportPartsTests {

	private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

	@Test
	void parts_changedAfterTheyAreCopied_stayAsTheyWere() {
		ObjectNode provenance = NODES.objectNode().put("seed", 42);
		ReportParts parts = new ReportParts(provenance, null);

		provenance.put("seed", 43);

		assertThat(parts.a()).isEqualTo(NODES.objectNode().put("seed", 42));
	}

	@Test
	void parts_changedByTheirReader_stayAsTheyWere() {
		ReportParts parts = new ReportParts(null, NODES.objectNode().put("seed", 42));

		((ObjectNode) Objects.requireNonNull(parts.b())).put("seed", 43);

		assertThat(parts.b()).isEqualTo(NODES.objectNode().put("seed", 42));
	}

	@Test
	void parts_aReportDoesNotHave_areNone() {
		JsonNode none = new ReportParts(null, null).a();

		assertThat(none).isNull();
	}

}
