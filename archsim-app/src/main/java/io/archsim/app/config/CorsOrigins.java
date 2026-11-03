package io.archsim.app.config;

import java.util.Arrays;
import java.util.List;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The web origins that may call the API from a browser. {@code ARCHSIM_CORS_ORIGINS}
 * gives them as a JSON array, as the Python API reads it
 * ({@code ["https://archsim.example.com"]}); a comma-separated list is accepted too.
 *
 * @param origins the origins, such as {@code https://archsim.example.com}
 */
public record CorsOrigins(List<String> origins) {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
	};

	/**
	 * The origins, copied.
	 * @param origins the origins
	 */
	public CorsOrigins {
		origins = List.copyOf(origins);
	}

	/**
	 * Reads the setting. Spring Boot's binder finds this method by its name.
	 * @param setting a JSON array of origins, or origins separated by commas
	 * @return the origins
	 * @throws IllegalArgumentException when the setting starts as a JSON array but is not
	 * one of strings
	 */
	public static CorsOrigins valueOf(String setting) {
		String text = setting.strip();
		if (text.isEmpty() || text.charAt(0) != '[') {
			return new CorsOrigins(
					Arrays.stream(text.split(",")).map(String::strip).filter((origin) -> !origin.isEmpty()).toList());
		}
		try {
			return new CorsOrigins(JSON.readValue(text, STRINGS));
		}
		catch (JacksonException ex) {
			throw new IllegalArgumentException("ARCHSIM_CORS_ORIGINS is not a JSON array of strings: " + text, ex);
		}
	}

}
