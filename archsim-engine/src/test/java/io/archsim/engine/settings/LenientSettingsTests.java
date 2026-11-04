package io.archsim.engine.settings;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.component.standard.StandardComponents;

import static org.assertj.core.api.Assertions.assertThat;

/** A node's settings read as a design that may not be valid yet is read. */
class LenientSettingsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void number_aNumber_isItsValue() {
		assertThat(settings("{\"replicas\": 3}").number("replicas")).hasValue(3.0);
	}

	@Test
	void number_aBooleanOrText_isMissing() {
		LenientSettings settings = settings("{\"replicas\": true, \"workers\": \"four\"}");

		assertThat(settings.number("replicas")).isEmpty();
		assertThat(settings.number("workers")).isEmpty();
	}

	@Test
	void isTrue_onlyTheValueTrue_isTrue() {
		LenientSettings settings = settings("{\"a\": true, \"b\": 1, \"c\": \"true\"}");

		assertThat(settings.isTrue("a")).isTrue();
		assertThat(settings.isTrue("b")).isFalse();
		assertThat(settings.isTrue("c")).isFalse();
	}

	@Test
	void truthy_aMissingSetting_isWhatTheCallerSays() {
		assertThat(settings("{}").truthy("populateOnMiss", true)).isTrue();
	}

	@Test
	void truthy_aSetSetting_isPythonsTruth() {
		assertThat(settings("{\"populateOnMiss\": \"no\"}").truthy("populateOnMiss", false)).isTrue();
	}

	@Test
	void meanMs_aDistribution_isItsMean() {
		assertThat(settings("{\"serviceTimeMs\": {\"kind\": \"uniform\", \"min\": 1, \"max\": 3}}")
			.meanMs("serviceTimeMs")).isEqualTo(2.0);
	}

	@Test
	void meanMs_somethingElse_isZero() {
		assertThat(settings("{\"serviceTimeMs\": 5}").meanMs("serviceTimeMs")).isZero();
	}

	@Test
	void meanMs_aDistributionWithAnExtraField_isItsMean() {
		assertThat(settings("{\"serviceTimeMs\": {\"kind\": \"constant\", \"value\": 4, \"note\": \"x\"}}")
			.meanMs("serviceTimeMs")).isEqualTo(4.0);
	}

	@Test
	void meanMs_aValueOutOfRange_isReadAsItIs() {
		assertThat(settings("{\"serviceTimeMs\": {\"kind\": \"constant\", \"value\": -2}}").meanMs("serviceTimeMs"))
			.isEqualTo(-2.0);
	}

	@Test
	void nonZero_zero_isMissing() {
		assertThat(settings("{\"replicas\": 0}").nonZero("replicas")).isEmpty();
	}

	@Test
	void text_aString_isItsValue() {
		assertThat(settings("{\"technology\": \"redis\", \"shards\": 2}").text("technology")).hasValue("redis");
	}

	@Test
	void loadCurve_anObjectRatherThanAList_isFlat() {
		LoadCurve curve = settings("{\"serviceTimeByLoad\": {\"a\": {\"rps\": 10, \"factor\": 2}}}")
			.loadCurve("serviceTimeByLoad");

		assertThat(curve.isEmpty()).isTrue();
	}

	@Test
	void ofNode_aValidConfig_hasEveryDefault() {
		LenientSettings cache = LenientSettings.ofNode(StandardComponents.registry().find("cache"),
				JSON.readTree("{}"));

		assertThat(cache.number("hitRatio")).hasValue(0.8);
	}

	@Test
	void ofNode_aConfigThatIsNotValid_isReadAsWritten() {
		LenientSettings cache = LenientSettings.ofNode(StandardComponents.registry().find("cache"),
				JSON.readTree("{\"hitRatio\": \"high\"}"));

		assertThat(cache.number("hitRatio")).isEmpty();
	}

	@Test
	void overDefaults_aMissingSetting_hasItsTypesDefault() {
		LenientSettings cache = LenientSettings.overDefaults(StandardComponents.registry().find("cache").orElseThrow(),
				JSON.readTree("{}"));

		assertThat(cache.number("ttlSeconds")).isPresent();
	}

	private static LenientSettings settings(String json) {
		return LenientSettings.of(JSON.readTree(json));
	}

}
