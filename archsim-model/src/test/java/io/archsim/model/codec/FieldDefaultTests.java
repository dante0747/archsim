package io.archsim.model.codec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.archsim.model.codec.SampleRecords.ConstantOfAnotherType;
import io.archsim.model.codec.SampleRecords.InstanceFieldDefault;
import io.archsim.model.codec.SampleRecords.MissingConstant;
import io.archsim.model.codec.SampleRecords.Schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

/**
 * A default a record names with {@link DefaultFrom}: the constant fills a field the input
 * leaves out and the JSON Schema does not show it; a name that is no static constant of
 * the field's type fails when the codec first meets the record.
 */
class FieldDefaultTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	@Test
	void read_defaultFromLeftOut_takesTheConstant() {
		Schedule schedule = RecordCodec.read(JSON.readTree("{}"), Schedule.class);

		assertThat(schedule.window()).isSameAs(Schedule.WORKING_HOURS);
	}

	@Test
	void jsonSchema_defaultFrom_hidesTheDefaultAndRequiresNothing() {
		ObjectNode schema = RecordCodec.jsonSchema(Schedule.class);

		assertThat(schema.get("properties").get("window").has("default")).isFalse();
		assertThat(schema.has("required")).isFalse();
	}

	@ParameterizedTest(name = "{0}")
	@ValueSource(classes = { MissingConstant.class, InstanceFieldDefault.class })
	void jsonSchema_defaultFromNamingNoStaticConstant_failsWithIllegalState(Class<? extends Record> type) {
		assertThatIllegalStateException().isThrownBy(() -> RecordCodec.jsonSchema(type))
			.withMessageContaining(" names no static constant ");
	}

	@Test
	void jsonSchema_defaultFromConstantOfAnotherType_failsWithIllegalState() {
		assertThatIllegalStateException().isThrownBy(() -> RecordCodec.jsonSchema(ConstantOfAnotherType.class))
			.withMessageEndingWith(" is not one: nine to five");
	}

}
