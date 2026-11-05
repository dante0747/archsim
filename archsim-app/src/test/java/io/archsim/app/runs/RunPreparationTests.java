package io.archsim.app.runs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.engine.run.RunPreparer;
import io.archsim.engine.run.SimulationLimitException;
import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Preparing a run with the engine's {@code RunPreparer}: the request's limits and seed go
 * in, and the run comes out as the runs' code plans it.
 */
class RunPreparationTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final RunPreparation preparation = new RunPreparation(
			new RunPreparer(StandardComponents.registry(), PriceSnapshots.shipped()));

	@Test
	void prepare_aSeed_isTheSeedTheRunSimulatesWith() throws IOException {
		PlannedRun planned = this.preparation.prepare(new PrepareRequest(starter(), null, 2_000_000, 300.0, 7L));

		assertThat(planned.seed()).isEqualTo(7);
	}

	@Test
	void prepare_noSeed_keepsTheDesigns() throws IOException {
		PlannedRun planned = this.preparation.prepare(new PrepareRequest(starter(), null, 2_000_000, 300.0, null));

		assertThat(planned.seed()).isEqualTo(42);
	}

	@Test
	void prepare_aDesign_storesItAsLoadedNotAsResolved() throws IOException {
		ObjectNode starter = starter();

		PlannedRun planned = this.preparation
			.prepare(new PrepareRequest(starter, "three-api-replicas", 2_000_000, 300.0, null));

		assertThat(planned.document()).isEqualTo(DesignDocuments.toJson(DesignDocuments.load(starter)));
		assertThat(planned.document().path("scenarios").isEmpty()).isFalse();
	}

	@Test
	void prepare_aScenario_namesItAndSaysWhetherAQuickCheckCanAnswerIt() throws IOException {
		PlannedRun planned = this.preparation
			.prepare(new PrepareRequest(starter(), "three-api-replicas", 2_000_000, 300.0, null));

		assertThat(planned.scenarioId()).isEqualTo("three-api-replicas");
		assertThat(planned.scenarioName()).isEqualTo("3 API replicas");
		assertThat(planned.quickCheck().eligible()).isFalse();
		assertThat(planned.quickCheck().reason()).startsWith("The run is too short to gain from a quick check");
	}

	@Test
	void prepare_moreRequestsThanTheLimit_isRefusedNamingTheLimit() {
		assertThatExceptionOfType(SimulationLimitException.class)
			.isThrownBy(() -> this.preparation.prepare(new PrepareRequest(starter(), null, 1000, 300.0, null)))
			.withMessage("This run would generate about 24,000 requests, above the limit of 1,000. "
					+ "Reduce RPS or duration.");
	}

	@Test
	void prepare_aDesignItCannotRead_isInvalid() {
		ObjectNode unreadable = JSON.createObjectNode().put("kind", "nope");

		assertThatExceptionOfType(DocumentInvalidException.class)
			.isThrownBy(() -> this.preparation.prepare(new PrepareRequest(unreadable, null, 1000, 300.0, null)));
	}

	private static ObjectNode starter() throws IOException {
		Path root = Path.of(System.getProperty("archsim.repoRoot", ".."));
		return (ObjectNode) JSON.readTree(Files.readString(root.resolve("examples/cache-aside-starter.json")));
	}

}
