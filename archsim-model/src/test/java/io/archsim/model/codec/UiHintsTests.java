package io.archsim.model.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.codec.SampleRecords.ConditionOnBooleanNotTrueOrFalse;
import io.archsim.model.codec.SampleRecords.ConditionOnList;
import io.archsim.model.codec.SampleRecords.ConditionOnUnknownChoice;
import io.archsim.model.codec.SampleRecords.ConditionOnUnknownField;
import io.archsim.model.codec.SampleRecords.ConditionWithoutValue;
import io.archsim.model.codec.SampleRecords.Conditions;
import io.archsim.model.codec.SampleRecords.Panel;
import io.archsim.model.text.CanonicalJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/**
 * The editor's hints on a field: the JSON Schema carries the {@link Ui} attributes a
 * field sets as its {@code x-ui} object, with a {@code visibleWhen} condition typed like
 * the field it tests.
 */
class UiHintsTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	/**
	 * The {@code visibleWhen} condition of a field of {@link Conditions}, as Python's
	 * {@code json.dumps(sort_keys=True)} writes it.
	 */
	private static String visibleWhen(String field) {
		return CanonicalJson.write(
				RecordCodec.jsonSchema(Conditions.class).get("properties").get(field).get("x-ui").get("visibleWhen"));
	}

	private static String canonical(String json) {
		return CanonicalJson.write(JSON.readTree(json));
	}

	@Test
	void jsonSchema_uiHints_writeOnlyTheAttributesSetUnderXUi() {
		String schema = CanonicalJson.write(RecordCodec.jsonSchema(Panel.class));

		assertThat(schema).isEqualTo(canonical("""
				{"additionalProperties": false, "title": "Panel", "type": "object",
				 "properties": {
				   "rate": {"title": "Rate", "type": "number",
				            "x-ui": {"label": "Request rate", "unit": "req/s", "group": "Load", "assumption": true,
				                     "step": 0.5}},
				   "cluster": {"title": "Cluster", "type": "string",
				               "x-ui": {"headline": true, "kind": "loadCurve", "refType": "kubernetes_cluster",
				                        "hidden": true}},
				   "plain": {"title": "Plain", "type": "number", "x-ui": {}},
				   "unmarked": {"title": "Unmarked", "type": "number"}},
				 "required": ["rate", "cluster", "plain", "unmarked"]}"""));
	}

	@Test
	void jsonSchema_visibleWhenOnBooleanField_isABoolean() {
		assertThat(visibleWhen("byFlag")).isEqualTo(canonical("{\"autoscaling\": true}"));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@CsvSource(delimiter = '|', textBlock = """
			byCount | {"replicas": 3}
			byShare | {"share": 0.5}
			""")
	void jsonSchema_visibleWhenOnNumberField_isANumber(String field, String condition) {
		assertThat(visibleWhen(field)).isEqualTo(canonical(condition));
	}

	@ParameterizedTest(name = "{0}: {1}")
	@CsvSource(delimiter = '|', textBlock = """
			byMode | {"mode": "ramp"}
			byZone | {"zone": "eu-west"}
			""")
	void jsonSchema_visibleWhenOnEnumOrStringField_isAString(String field, String condition) {
		assertThat(visibleWhen(field)).isEqualTo(canonical(condition));
	}

	@Test
	void jsonSchema_visibleWhenWithoutValue_failsWithIllegalArgument() {
		assertThatIllegalArgumentException().isThrownBy(() -> RecordCodec.jsonSchema(ConditionWithoutValue.class))
			.withMessage("visibleWhen is field=value: autoscaling");
	}

	@Test
	void jsonSchema_visibleWhenNamingUnknownField_failsWithIllegalArgument() {
		assertThatIllegalArgumentException().isThrownBy(() -> RecordCodec.jsonSchema(ConditionOnUnknownField.class))
			.withMessageEndingWith(" has no field autoscaling");
	}

	@Test
	void jsonSchema_visibleWhenOnListField_failsWithIllegalArgument() {
		assertThatIllegalArgumentException().isThrownBy(() -> RecordCodec.jsonSchema(ConditionOnList.class))
			.withMessageStartingWith("visibleWhen cannot test tags");
	}

	@Test
	void jsonSchema_visibleWhenOnBooleanNeitherTrueNorFalse_failsWithIllegalArgument() {
		assertThatIllegalArgumentException()
			.isThrownBy(() -> RecordCodec.jsonSchema(ConditionOnBooleanNotTrueOrFalse.class))
			.withMessage("visibleWhen tests autoscaling, a boolean, against 'yes': write true or false");
	}

	@Test
	void jsonSchema_visibleWhenOnChoiceNotAmongTheChoices_failsWithIllegalArgument() {
		assertThatIllegalArgumentException().isThrownBy(() -> RecordCodec.jsonSchema(ConditionOnUnknownChoice.class))
			.withMessage("visibleWhen tests mode against 'rmap', which is not one of [auto, steady, ramp]");
	}

}
