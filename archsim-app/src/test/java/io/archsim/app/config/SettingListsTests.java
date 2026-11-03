package io.archsim.app.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** List settings, read as the Python API's settings read their host lists. */
class SettingListsTests {

	@Test
	void parse_jsonArray_isItsItemsAsTheyAre() {
		assertThat(SettingLists.parse("S", "[\" a \", \"b\"]")).containsExactly(" a ", "b");
	}

	@Test
	void parse_commaSeparated_isTheStrippedItemsWithoutEmptyOnes() {
		assertThat(SettingLists.parse("S", " a , b,, ")).containsExactly("a", "b");
	}

	@Test
	void parse_brokenJsonArray_failsNamingTheSetting() {
		assertThatIllegalArgumentException().isThrownBy(() -> SettingLists.parse("ARCHSIM_X", "[\"a\", 1"))
			.withMessageStartingWith("ARCHSIM_X is not a JSON array of strings");
	}

	@Test
	void valueOf_hostListWithBlankItems_leavesThemOut() {
		assertThat(HostList.valueOf("[\"web\", \" \"]").hosts()).containsExactly("web");
	}

}
