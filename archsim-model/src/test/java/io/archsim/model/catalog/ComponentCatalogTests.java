package io.archsim.model.catalog;

import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.Default;
import io.archsim.model.codec.RecordCodec;
import io.archsim.model.component.Category;
import io.archsim.model.component.ComponentIdentity;
import io.archsim.model.component.ComponentRegistry;
import io.archsim.model.component.ComponentSpec;
import io.archsim.model.component.standard.StandardComponents;
import io.archsim.parity.GoldenFile;
import io.archsim.parity.JsonDiff;
import io.archsim.parity.SchemaDepartures;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * The catalog, part by part, against the one the Python engine serves
 * ({@code catalog/components.json.gz}). {@code GoldenParityTests} checks it whole; these
 * tests name the component type and the field that differ.
 */
class ComponentCatalogTests {

	private static final JsonNode GOLDEN = GoldenFile.read("catalog/components")
		.entries("archsim_engine.registry.ComponentRegistry.catalog_document")
		.getFirst()
		.expectedOutput();

	private static final ObjectNode CATALOG = ComponentCatalog.of(StandardComponents.registry());

	@TestFactory
	Stream<DynamicTest> of_eachStandardType_describesItAsThePythonEngineDoes() {
		return GOLDEN.path("components").valueStream().map((expected) -> {
			String type = expected.path("type").asString();
			return DynamicTest.dynamicTest(type,
					() -> assertThat(JsonDiff.differences(expected, entryOf(type))).isEmpty());
		});
	}

	@TestFactory
	Stream<DynamicTest> technologyTraits_eachStandardType_areThoseThePythonEngineLists() {
		return GOLDEN.path("components").valueStream().map((expected) -> {
			String type = expected.path("type").asString();
			return DynamicTest.dynamicTest(type,
					() -> assertThat(JsonDiff.differences(expected.path("technologyTraits"), traitsOf(type)))
						.isEmpty());
		});
	}

	@Test
	void of_linkSettings_areDescribedAsThePythonEngineDoes() {
		assertThat(JsonDiff.differences(GOLDEN.path("edgeFields"), CATALOG.path("edgeFields"))).isEmpty();
	}

	@Test
	void of_workloadSettings_areDescribedAsThePythonEngineDoes() {
		assertThat(JsonDiff.differences(GOLDEN.path("workloadFields"),
				SchemaDepartures.asRecorded(CATALOG).path("workloadFields")))
			.isEmpty();
	}

	@Test
	void of_peakHoursPerMonth_isDescribedWithItsDefaultAndBounds() {
		JsonNode hours = CATALOG.path("workloadFields")
			.valueStream()
			.filter((field) -> "peakHoursPerMonth".equals(field.path("key").stringValue("")))
			.findFirst()
			.orElseThrow();

		assertThat(List.of(hours.path("default").doubleValue(), hours.path("nullable").booleanValue(),
				hours.path("min").doubleValue(), hours.path("max").doubleValue(), hours.path("unit").stringValue(""),
				hours.path("group").stringValue("")))
			.containsExactly(0.0, false, 0.0, 730.0, "h", "Traffic");
		assertThat(hours.path("default").isNumber()).isTrue();
	}

	@Test
	void of_experimentalBadge_saysWhatThePythonEngineSays() {
		assertThat(CATALOG.path("tierNote")).isEqualTo(GOLDEN.path("tierNote"));
	}

	@Test
	void of_standardRegistry_listsTheTypesInRegistryOrder() {
		Stream<String> types = CATALOG.path("components").valueStream().map((entry) -> entry.path("type").asString());

		assertThat(types).containsExactlyElementsOf(StandardComponents.registry().types());
	}

	@TestFactory
	Stream<DynamicTest> of_eachPreset_isAValidConfigOfItsType() {
		return StandardComponents.registry()
			.specs()
			.stream()
			.flatMap((spec) -> Presentation.of(spec.type())
				.presets()
				.stream()
				.map((preset) -> DynamicTest.dynamicTest(spec.type() + " " + preset.id(),
						() -> assertValidPreset(spec, preset))));
	}

	@Test
	void of_eachType_givesItsPresetsDistinctIds() {
		assertThat(StandardComponents.registry().types()).allSatisfy(
				(type) -> assertThat(Presentation.of(type).presets().stream().map(Preset::id)).doesNotHaveDuplicates());
	}

	@Test
	void of_typeWithoutPresentation_namesTheMissingResource() {
		ComponentRegistry registry = new ComponentRegistry(List.of(new GadgetSpec()));

		assertThatIllegalStateException().isThrownBy(() -> ComponentCatalog.of(registry))
			.withMessageContaining("io/archsim/model/catalog/gadget.json");
	}

	private static ObjectNode traitsOf(String type) {
		ObjectNode traits = JsonNodeFactory.instance.objectNode();
		ComponentCatalog.technologyTraits(type).forEach((technology, brought) -> {
			ArrayNode written = traits.putArray(technology);
			brought.forEach((trait) -> written.add(RecordCodec.write(trait)));
		});
		return traits;
	}

	/**
	 * Asserts that a preset is what the editor can apply: its config parses as the
	 * type's, with the technology the preset names, and it has a name, family and
	 * description.
	 */
	private static void assertValidPreset(ComponentSpec<?> spec, Preset preset) {
		Record config = spec.parseConfig(preset.config());

		assertThat(RecordCodec.write(config).path("technology").asString()).isEqualTo(preset.technology());
		assertThat(Stream.of(preset.name(), preset.family(), preset.description()))
			.allSatisfy((text) -> assertThat(text).isNotBlank());
	}

	private static JsonNode entryOf(String type) {
		return CATALOG.path("components")
			.valueStream()
			.filter((entry) -> entry.path("type").asString().equals(type))
			.findFirst()
			.orElseThrow(() -> new AssertionError("no catalog entry for " + type));
	}

	/** A component type nobody described for the editor. */
	private static final class GadgetSpec extends ComponentSpec<Gadget> {

		GadgetSpec() {
			super(new ComponentIdentity("gadget", "Gadget", Category.COMPUTE), Gadget.class, List.of());
		}

	}

	/**
	 * The gadget's config.
	 *
	 * @param size how big it is
	 */
	record Gadget(@Default("1") int size) {

	}

}
