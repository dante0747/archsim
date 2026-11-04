package io.archsim.model.catalog;

import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The typed facts of each component type, and that they agree with the catalog's JSON.
 */
class ComponentFactsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final ComponentFacts facts = new ComponentFacts(StandardComponents.registry());

	@Test
	void type_anUnknownType_isEmpty() {
		assertThat(this.facts.type("mystery")).isEmpty();
	}

	@Test
	void displayName_aDatabase_isWhatTheEditorCallsIt() {
		assertThat(type("database").displayName()).isEqualTo("Database");
	}

	@Test
	void settings_aCacheSettingThatDependsOnAnother_saysWhenItAppliesAndItsUnit() {
		Setting ttl = type("cache").settings()
			.stream()
			.filter((setting) -> setting.key().equals("ttlSeconds"))
			.findFirst()
			.orElseThrow();

		assertThat(ttl.unit()).isEqualTo("s");
		assertThat(ttl.kind()).isEqualTo("number");
		assertThat(ttl.assumption()).isTrue();
		assertThat(ttl.visibleWhen()).containsExactly(Map.entry("hitModel", JSON.readTree("\"ttl_keyspace\"")));
	}

	@TestFactory
	Stream<DynamicTest> settings_ofEachType_agreeWithTheCatalogsFieldDescriptors() {
		return ComponentCatalog.of(StandardComponents.registry())
			.path("components")
			.valueStream()
			.map((entry) -> DynamicTest.dynamicTest(entry.path("type").asString(),
					() -> assertThat(type(entry.path("type").asString()).settings()
						.stream()
						.map(ComponentFactsTests::descriptor)
						.toList())
						.isEqualTo(entry.path("fields").valueStream().map(ComponentFactsTests::described).toList())));
	}

	@Test
	void defaultValue_aSetting_isItsDefault() {
		assertThat(type("service").defaultValue("replicas")).isEqualTo(JSON.readTree("2"));
	}

	@Test
	void defaultValue_aSettingThatDefaultsToNothing_isNull() {
		assertThat(type("service").defaultValue("gcEveryRequests").isNull()).isTrue();
	}

	@Test
	void presets_ofADatabase_comeInTheEditorsOrder() {
		assertThat(type("database").presets()).extracting(Preset::name)
			.startsWith("PostgreSQL", "PostgreSQL + 2 read replicas", "MySQL", "SQL Server");
	}

	@Test
	void technologyDefault_aSettingTheTechnologyFillsIn_isItsValue() {
		assertThat(type("database").technologyDefault("rowLocks", "postgresql")).contains(JSON.readTree("\"on\""));
	}

	@Test
	void technologyDefault_aSettingTheProfileDoesNotName_isEmpty() {
		assertThat(type("database").technologyDefault("replicas", "postgresql")).isEmpty();
	}

	@Test
	void technologyDefault_aTechnologyTheTypeDoesNotKnow_isEmpty() {
		assertThat(type("database").technologyDefault("rowLocks", "mystery")).isEmpty();
	}

	@Test
	void technologySource_aDocumentedDefault_isTheDocumentationsLink() {
		assertThat(type("service").technologySource("gcPauseMs", "go")).contains("https://go.dev/doc/go1.8");
	}

	@Test
	void technologySource_anIllustrativeDefault_isEmpty() {
		assertThat(type("service").technologySource("gcPauseMs", "java")).isEmpty();
	}

	@Test
	void linkDefault_aWebClient_suggestsItsLatency() {
		assertThat(type("web_client").linkDefault("latencyMs"))
			.contains(JSON.readTree("{\"kind\": \"lognormal\", \"median\": 15.0, \"p95\": 45.0}"));
	}

	@Test
	void linkDefault_aTypeThatSuggestsNone_isEmpty() {
		assertThat(type("database").linkDefault("latencyMs")).isEmpty();
	}

	private TypeFacts type(String name) {
		return this.facts.type(name).orElseThrow();
	}

	/** The parts of a setting the catalog's descriptor also gives. */
	private static ObjectNode descriptor(Setting setting) {
		ObjectNode json = JsonNodeFactory.instance.objectNode()
			.put("key", setting.key())
			.put("label", setting.label())
			.put("unit", setting.unit())
			.put("kind", setting.kind())
			.put("assumption", setting.assumption());
		json.set("visibleWhen", JSON.valueToTree(setting.visibleWhen()));
		json.set("default", setting.defaultValue());
		return json;
	}

	/** The same parts of the catalog's descriptor. */
	private static ObjectNode described(JsonNode field) {
		ObjectNode json = JsonNodeFactory.instance.objectNode()
			.put("key", field.path("key").asString())
			.put("label", field.path("label").asString())
			.put("unit", field.path("unit").isString() ? field.path("unit").asString() : null)
			.put("kind", field.path("kind").asString())
			.put("assumption", field.path("assumption").asBoolean());
		json.set("visibleWhen", field.has("visibleWhen") ? field.get("visibleWhen") : JSON.createObjectNode());
		json.set("default", field.get("default"));
		return json;
	}

}
