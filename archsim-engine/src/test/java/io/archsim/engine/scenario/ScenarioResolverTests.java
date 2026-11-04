package io.archsim.engine.scenario;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.DesignDocuments;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.model.document.ArchitectureDocument;
import io.archsim.model.issue.DocumentInvalidException;
import io.archsim.model.pricing.PriceSnapshots;
import io.archsim.parity.Repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Resolving the document a run simulates, from the starter template. */
class ScenarioResolverTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ScenarioResolver resolver = new ScenarioResolver(StandardComponents.registry(),
			PriceSnapshots.shipped());

	@Test
	void resolve_theDesignItself_isAutoSizedWithItsPricesPinned() throws IOException {
		ResolvedDesign resolved = this.resolver.resolve(starter(), null);

		assertThat(resolved.scenario()).isEmpty();
		assertThat(resolved.sizes()).containsKey("api");
		assertThat(resolved.document().node("api").orElseThrow().config().has("cpuCores")).isTrue();
		assertThat(resolved.document().pricing().snapshotSha256()).isNotNull();
	}

	@Test
	void resolve_aScenario_appliesItsOverridesToTheSizedDesign() throws IOException {
		ResolvedDesign resolved = this.resolver.resolve(starter(), "three-api-replicas");

		assertThat(resolved.scenario())
			.hasValueSatisfying((scenario) -> assertThat(scenario.id()).isEqualTo("three-api-replicas"));
		assertThat(resolved.document().node("api").orElseThrow().config().path("replicas").intValue()).isEqualTo(3);
		assertThat(resolved.document().scenarios()).isEmpty();
	}

	@Test
	void resolve_aScenarioTheDesignDoesNotHave_isRefused() throws IOException {
		ArchitectureDocument design = starter();

		assertThatExceptionOfType(DocumentInvalidException.class)
			.isThrownBy(() -> this.resolver.resolve(design, "nope"))
			.satisfies((ex) -> assertThat(ex.issues().getFirst().code()).isEqualTo("scenario.unknown"));
	}

	private static ArchitectureDocument starter() throws IOException {
		Path starter = Repository.examples().resolve("cache-aside-starter.json");
		return DesignDocuments.load(JSON.readTree(Files.readString(starter)));
	}

}
