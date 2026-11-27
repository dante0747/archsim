package io.archsim.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The host names a server without sign-in answers to: this machine's own, whatever the
 * setting says, and the names the setting lists, without case.
 */
class AllowedHostsTests {

	private static final AllowedHosts NONE = AllowedHosts.valueOf("");

	@ParameterizedTest
	@ValueSource(strings = { "localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3", "[::1]" })
	void allows_thisMachinesNameWithNoSetting_isTrue(String host) {
		assertThat(NONE.allows(host)).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "attacker.example", "127.evil.example", "127.0.0.1.nip.io", "api", "0.0.0.0", "", "::1" })
	void allows_anotherNameWithNoSetting_isFalse(String host) {
		assertThat(NONE.allows(host)).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "api", "API", "archsim.lan" })
	void allows_aNameTheSettingLists_isTrue(String host) {
		assertThat(AllowedHosts.valueOf("api, Archsim.lan").allows(host)).isTrue();
	}

	@Test
	void allows_anEmptyNameWithABlankInTheSetting_isFalse() {
		assertThat(AllowedHosts.valueOf("[\"\", \"api\"]").allows("")).isFalse();
	}

}
