package io.archsim.app.config;

import java.util.List;

/**
 * The web origins that may call the API from a browser. {@code ARCHSIM_CORS_ORIGINS}
 * gives them as a JSON array, as the Python API reads it
 * ({@code ["https://archsim.example.com"]}); a comma-separated list is accepted too.
 *
 * @param origins the origins, such as {@code https://archsim.example.com}
 */
public record CorsOrigins(List<String> origins) {

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
		return new CorsOrigins(SettingLists.parse("ARCHSIM_CORS_ORIGINS", setting));
	}

}
