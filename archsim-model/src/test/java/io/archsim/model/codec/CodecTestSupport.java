package io.archsim.model.codec;

import java.util.ArrayList;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import io.archsim.model.text.CanonicalJson;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * What the codec's tests read inputs and expect errors and schemas with.
 */
final class CodecTestSupport {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private CodecTestSupport() {
	}

	/**
	 * JSON from its text.
	 * @param text the JSON
	 * @return the tree
	 */
	static JsonNode json(String text) {
		return JSON.readTree(text);
	}

	/**
	 * The errors reading a JSON input into a record gives.
	 * @param text the input
	 * @param type the record or union
	 * @return the errors, which there must be
	 */
	static List<BindingError> errors(String text, Class<?> type) {
		return errors(json(text), type);
	}

	/**
	 * The errors reading a JSON tree into a record gives, for an input JSON text cannot
	 * carry.
	 * @param input the input
	 * @param type the record or union
	 * @return the errors, which there must be
	 */
	static List<BindingError> errors(JsonNode input, Class<?> type) {
		return catchThrowableOfType(BindingException.class, () -> RecordCodec.read(input, type)).errors();
	}

	/**
	 * An error as pydantic reports it.
	 * @param type pydantic's error type
	 * @param location where it is, its parts joined by dots, digits an index
	 * @param message pydantic's message
	 * @return the error
	 */
	static BindingError error(String type, String location, String message) {
		List<Object> parts = new ArrayList<>();
		if (!location.isEmpty()) {
			for (String part : location.split("[.]", -1)) {
				parts.add(part.chars().allMatch(Character::isDigit) ? Integer.valueOf(part) : part);
			}
		}
		return new BindingError(type, new Location(parts), message);
	}

	/**
	 * Checks a record's JSON Schema against pydantic's, both written as Python's
	 * {@code json.dumps(sort_keys=True)} writes them.
	 * @param type the record
	 * @param pydantic the schema pydantic writes
	 */
	static void assertSchema(Class<? extends Record> type, String pydantic) {
		assertThat(CanonicalJson.write(RecordCodec.jsonSchema(type))).isEqualTo(CanonicalJson.write(json(pydantic)));
	}

}
