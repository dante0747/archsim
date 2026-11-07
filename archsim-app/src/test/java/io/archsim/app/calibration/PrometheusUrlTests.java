package io.archsim.app.calibration;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import io.archsim.engine.calibration.prometheus.PrometheusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * A Prometheus URL split as Python 3.12's {@code urllib.parse.urlsplit} splits it, and
 * its host matched against the allowlist as the Python engine's {@code host_allowed}
 * matches it. The check and the connection read the host from the same split, so no
 * spelling can pass one and reach another.
 */
class PrometheusUrlTests {

	/**
	 * Each URL with its scheme, host name, port and path in brackets, as {@code urlsplit}
	 * reads them, {@code None} for none.
	 */
	@ParameterizedTest
	@CsvSource(delimiter = ';',
			value = { "http://Prom.Internal:9090/x;http prom.internal 9090 [/x]", "HTTP://prom;http prom None []",
					"'  http://prom/';http prom None [/]", "http://user:pw@prom:9090/api;http prom 9090 [/api]",
					"http://[::1]:9090;http ::1 9090 []", "https://[FE80::1%25Eth0]/;https fe80::1%25Eth0 None [/]",
					"http://prom:;http prom None []", "http://prom:0;http prom 0 []",
					"http://prom:9090?a=b#c;http prom 9090 []", "http://prom#x/y;http prom None []" })
	void split_url_isWhatUrlsplitReads(String url, String parts) {
		PrometheusUrl split = PrometheusUrl.split(url);
		Integer port = split.port();

		assertThat(split.scheme() + " " + split.hostname() + " " + ((port != null) ? port : "None") + " ["
				+ split.path() + "]")
			.isEqualTo(parts);
	}

	@Test
	void split_tabsAndLineBreaks_areRemovedBeforeAnythingIsRead() {
		PrometheusUrl split = PrometheusUrl.split((char) 1 + " http://pr\tom\n:90\r90/x");

		assertThat(split.hostname()).isEqualTo("prom");
		assertThat(split.port()).isEqualTo(9090);
		assertThat(split.path()).isEqualTo("/x");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "file:///etc/passwd|file|", "prom:9090|prom|", "ftp://prom|ftp|prom",
			"//prom:9090|''|prom:9090", "http:prom|http|" })
	void split_urlWithoutAnHttpHost_keepsWhatUrlsplitKeeps(String url, String scheme, String netloc) {
		PrometheusUrl split = PrometheusUrl.split(url);

		assertThat(split.scheme()).isEqualTo(scheme);
		assertThat(split.netloc()).isEqualTo((netloc != null) ? netloc : "");
	}

	@ParameterizedTest
	@CsvSource({ "http://prom:abc", "http://prom:70000", "http://prom:-1", "http://prom:+80", "http://prom:٩٠" })
	void port_thatIsNotOne_isRefusedWithoutRepeatingIt(String url) {
		PrometheusUrl split = PrometheusUrl.split(url);

		assertThatExceptionOfType(PrometheusException.class).isThrownBy(split::port)
			.withMessage("the Prometheus URL has an invalid port");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "http://[::1|Invalid IPv6 URL", "http://::1]/|Invalid IPv6 URL",
			"http://x[::1]:9090|Invalid IPv6 URL", "http://[::1]x:9090|Invalid IPv6 URL",
			"http://[127.0.0.1]|An IPv4 address cannot be in brackets", "http://[vz.x]|IPvFuture address is invalid" })
	void split_brokenBrackets_isRefusedInPythonsWords(String url, String message) {
		assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> PrometheusUrl.split(url))
			.withMessage(message);
	}

	/**
	 * Python's {@code urlsplit} repeats a bracketed host that is no address
	 * ({@code 'prom' does not appear to be an IPv4 or IPv6 address}); Java does not
	 * repeat what a request sent.
	 */
	@Test
	void split_bracketedHostThatIsNoAddress_isRefusedWithoutRepeatingIt() {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> PrometheusUrl.split("http://[SECRET-host]:9090"))
			.withMessage("Invalid IPv6 URL");
	}

	/**
	 * A host that IDNA's NFKC normalization would turn into another, such as one with a
	 * full-width solidus ({@code prom／evil} into {@code prom/evil}), is refused, as
	 * Python refuses it, without repeating it as Python does.
	 */
	@Test
	void split_netlocThatNormalizesIntoADelimiter_isRefusedWithoutRepeatingIt() {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> PrometheusUrl.split("http://prom／SECRET:9090/"))
			.withMessage("netloc contains invalid characters under NFKC normalization");
	}

	@Test
	void allowed_noHostsListed_allowsAnyHost() {
		assertThat(PrometheusUrl.allowed("http://anything:1", List.of())).isTrue();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "http://Prom.Internal:9090/x|prom.internal|true",
			"http://prom.internal:9091|prom.internal:9090|false", "https://prom.internal|prom.internal:443|true",
			"http://[::1]:9090|[::1]:9090|true", "http://prom.internal.evil.example|prom.internal|false",
			"http://prom:9090|' PROM:9090 '|true", "http://[::1]:9090|::1|true", "http://prom:9090|prom:09090|true",
			"http://prom|prom:80|true", "http://prom:0|prom:80|true", "https://prom|prom:80|false",
			"http://prom:9090|other, prom|false", "http://prom:9090|[prom]|true", "http://prom:9090|prom:|false",
			"http://evil@prom:9090|prom:9090|true", "http://prom@evil:9090|prom:9090|false" })
	void allowed_entry_matchesAsThePythonEngineMatchesIt(String url, String entry, boolean expected) {
		assertThat(PrometheusUrl.allowed(url, List.of(entry))).isEqualTo(expected);
	}

}
