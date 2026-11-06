package io.archsim.engine.imports;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.JsonNodeFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * URL hosts as Python's {@code urlparse(value).hostname} reads them.
 */
class UrlHostsTests {

	@Test
	void of_urlWithUserPasswordAndPort_isTheHostInLowerCase() {
		assertThat(host("redis://User:Secret@CACHE:6379/0")).contains("cache");
	}

	@Test
	void of_hostWithAnUnderscore_isTheHost() {
		assertThat(host("postgresql://orders_db:5432/shop")).contains("orders_db");
	}

	@Test
	void of_ipv6AddressInBrackets_isTheAddress() {
		assertThat(host("http://[::1]:8080/x")).contains("::1");
	}

	@Test
	void of_bracketThatIsNotClosed_isNone() {
		assertThat(host("http://[::1")).isEmpty();
	}

	@Test
	void of_spaceAfterTheHost_isKeptAsPythonKeepsIt() {
		assertThat(host("redis://cache ")).contains("cache ");
	}

	@Test
	void of_textWithoutAScheme_isNone() {
		assertThat(host("cache:6379")).isEmpty();
	}

	@Test
	void of_number_isNone() {
		assertThat(UrlHosts.of(JsonNodeFactory.instance.numberNode(42))).isEmpty();
	}

	private static Optional<String> host(String url) {
		return UrlHosts.of(JsonNodeFactory.instance.stringNode(url));
	}

}
