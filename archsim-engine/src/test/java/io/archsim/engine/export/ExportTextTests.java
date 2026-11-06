package io.archsim.engine.export;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The names and words every export shares.
 */
class ExportTextTests {

	@Test
	void dnsName_idWithCapitalsDotsAndUnderscores_isLowerCaseWithDashes() {
		assertThat(ExportText.dnsName("API.Gateway_v2")).isEqualTo("api-gateway-v2");
	}

	@Test
	void dnsName_nothingDnsAllows_isNode() {
		assertThat(ExportText.dnsName("!!!")).isEqualTo("node");
	}

	@Test
	void slug_longName_isCutToFortyCharacters() {
		assertThat(ExportText.slug("A very long design name that goes on and on past forty characters"))
			.isEqualTo("a-very-long-design-name-that-goes-on-and");
	}

	@Test
	void slug_emptyName_isArchitecture() {
		assertThat(ExportText.slug("")).isEqualTo("architecture");
	}

}
