package io.archsim.app.config;

import java.util.Arrays;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads a list setting as the Python API's settings read their host lists: a JSON array
 * as it is, or items separated by commas, each stripped, the empty ones left out.
 */
final class SettingLists {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
	};

	private SettingLists() {
	}

	/**
	 * Reads a list setting.
	 * @param name the setting, for the message, such as {@code ARCHSIM_CORS_ORIGINS}
	 * @param setting the setting's value
	 * @return the items
	 * @throws IllegalArgumentException when the value starts as a JSON array but is not
	 * one of strings
	 */
	static List<String> parse(String name, String setting) {
		String text = setting.strip();
		if (text.isEmpty() || text.charAt(0) != '[') {
			return Arrays.stream(text.split(",")).map(String::strip).filter((item) -> !item.isEmpty()).toList();
		}
		try {
			return JSON.readValue(text, STRINGS);
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException(name + " is not a JSON array of strings: " + text, ex);
		}
	}

}
