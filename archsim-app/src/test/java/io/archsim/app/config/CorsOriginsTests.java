package io.archsim.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** The CORS origins setting, written as the Python API reads it or as a plain list. */
class CorsOriginsTests {

	@Test
	void valueOf_jsonArray_givesItsItems() {
		CorsOrigins origins = CorsOrigins.valueOf("[\"http://localhost:3000\", \"https://archsim.test\"]");

		assertThat(origins.origins()).containsExactly("http://localhost:3000", "https://archsim.test");
	}

	@Test
	void valueOf_commaSeparated_givesTheTrimmedItems() {
		CorsOrigins origins = CorsOrigins.valueOf(" a.example , b.example,, ");

		assertThat(origins.origins()).containsExactly("a.example", "b.example");
	}

	@Test
	void valueOf_brokenJsonArray_failsNamingTheSetting() {
		assertThatIllegalArgumentException().isThrownBy(() -> CorsOrigins.valueOf("[\"a\", 1"))
			.withMessageStartingWith("ARCHSIM_CORS_ORIGINS is not a JSON array of strings");
	}

}
