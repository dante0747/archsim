package io.archsim.engine.calibration.prometheus.http;

import java.nio.file.Path;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.stream.Stream;

import javax.net.ssl.SSLContext;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.engine.calibration.prometheus.PrometheusException;
import io.archsim.engine.calibration.prometheus.http.StubPrometheus.Request;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Calling Prometheus from a hosted server: only public addresses, one resolution per
 * query, the connection pinned to the address that was checked, TLS verified against the
 * host name while connecting to that address, and no redirects. From the Python engine's
 * {@code test_calibration_outbound.py}, with DNS rebinding, every redirect status and
 * more special addresses.
 */
class HttpPrometheusOutboundTests {

	private static final double START = 1_700_000_000.0;

	private static final long MAX_BYTES = 50L * 1024 * 1024;

	private static final String NAME = "prom.example.test";

	private static final String NON_PUBLIC = " a loopback, private, link-local or otherwise non-public address, "
			+ "which this server does not call";

	private static TestCertificates forName;

	private static TestCertificates forOtherName;

	@BeforeAll
	static void makeCertificates(@TempDir Path certificates) {
		forName = TestCertificates.forName(certificates, NAME);
		forOtherName = TestCertificates.forName(certificates, "other.test");
	}

	@ParameterizedTest
	@MethodSource("nonPublicAnswers")
	void queryRange_nameResolvingToANonPublicAddress_isRefusedBeforeConnecting(List<String> addresses) {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			TableResolver resolver = TableResolver.answering(NAME, addresses);

			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(NAME), List.of(NAME), true), resolver))
				.withMessage(NAME + " resolves to" + NON_PUBLIC);
			assertThat(resolver.asked()).containsExactly(NAME);
			assertThat(prometheus.requests()).isEmpty();
		}
	}

	static Stream<List<String>> nonPublicAnswers() {
		return Stream.of(List.of("127.0.0.1"), List.of("10.0.0.8"), List.of("192.168.0.4"), List.of("169.254.169.254"),
				List.of("100.64.12.1"), List.of("fd12::1"), List.of("fe80::1"), List.of("::ffff:127.0.0.1"),
				// one non-public answer is enough to refuse
				List.of("8.8.8.8", "10.0.0.8"), List.of("2606:4700:4700::1111", "::1"),
				// and the special ranges around them
				List.of("169.254.0.1"), List.of("127.255.255.254"), List.of("::1"), List.of("0.0.0.0"), List.of("::"),
				List.of("::ffff:169.254.169.254"), List.of("::ffff:10.0.0.1"), List.of("64:ff9b::a9fe:a9fe"),
				List.of("64:ff9b::7f00:1"), List.of("224.0.0.1"), List.of("ff02::1"), List.of("192.0.2.1"),
				List.of("2001:db8::1"), List.of("fec0::1"));
	}

	@ParameterizedTest
	@CsvSource({ "http://127.0.0.1:9, 127.0.0.1", "http://[::1]:9, ::1", "http://10.0.0.1:9, 10.0.0.1",
			"http://169.254.169.254, 169.254.169.254", "http://[::ffff:169.254.169.254], ::ffff:169.254.169.254",
			"http://[::ffff:7f00:1]:9, ::ffff:7f00:1", "http://[64:ff9b::a9fe:a9fe], 64:ff9b::a9fe:a9fe",
			"http://[fe80::1]:9, fe80::1", "http://[fd00::1]:9, fd00::1", "http://0.0.0.0:9, 0.0.0.0",
			"http://[::]:9, ::", "http://100.64.0.1:9, 100.64.0.1" })
	void queryRange_nonPublicLiteralTheAllowlistDoesNotName_isRefused(String url, String host) {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> query(server(url, List.of(), true), HostResolver.SYSTEM))
			.withMessage(host + " is" + NON_PUBLIC);
	}

	@Test
	void queryRange_loopbackLiteralTheAllowlistNames_isCalled() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			query(server(prometheus.url(), List.of("127.0.0.1:" + prometheus.port()), true), HostResolver.SYSTEM);

			assertThat(prometheus.requests()).extracting(Request::host)
				.containsExactly("127.0.0.1:" + prometheus.port());
		}
	}

	@Test
	void queryRange_name_isResolvedOnceAndCalledAtThatAddressByItsName() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			TableResolver resolver = TableResolver.answering(NAME, List.of("127.0.0.1"));

			query(server(prometheus.url(NAME), List.of(), false), resolver);

			assertThat(resolver.asked()).containsExactly(NAME);
			// the Host header keeps the name
			assertThat(prometheus.requests()).extracting(Request::host).containsExactly(NAME + ":" + prometheus.port());
		}
	}

	/**
	 * A name beyond ASCII is looked up, sent and checked in its IDNA form, as Python's
	 * {@code idna} codec writes it for {@code getaddrinfo}, the {@code Host} header and
	 * TLS.
	 */
	@Test
	void queryRange_nameBeyondAscii_isCalledByItsIdnaForm() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			TableResolver resolver = TableResolver.answering("xn--prm-tna.example.test", List.of("127.0.0.1"));

			query(server(prometheus.url("pröm.example.test"), List.of("pröm.example.test"), false), resolver);

			assertThat(resolver.asked()).containsExactly("xn--prm-tna.example.test");
			assertThat(prometheus.requests()).extracting(Request::host)
				.containsExactly("xn--prm-tna.example.test:" + prometheus.port());
		}
	}

	/**
	 * A name that answers one address when it is checked and another when the client
	 * connects (DNS rebinding) cannot move the call: the client asks once and connects to
	 * that answer. Asked again, the name here would answer an address nobody listens on.
	 */
	@Test
	void queryRange_nameThatAnswersOtherwiseLater_isCalledAtItsFirstAnswer() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			TableResolver resolver = TableResolver.rebinding(NAME, List.of("127.0.0.1"), List.of("10.255.255.1"));

			query(server(prometheus.url(NAME), List.of(NAME), false), resolver);

			assertThat(resolver.asked()).containsExactly(NAME);
			assertThat(prometheus.requests()).extracting(Request::host).containsExactly(NAME + ":" + prometheus.port());
		}
	}

	/**
	 * The check reads the same one answer: a name that answers a private address first
	 * and the server's address after is refused, and never asked again.
	 */
	@Test
	void queryRange_nameThatAnswersPrivateFirst_isRefusedAndNeverAskedAgain() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			TableResolver resolver = TableResolver.rebinding(NAME, List.of("10.0.0.8"), List.of("127.0.0.1"));

			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(NAME), List.of(NAME), true), resolver))
				.withMessage(NAME + " resolves to" + NON_PUBLIC);
			assertThat(resolver.asked()).containsExactly(NAME);
			assertThat(prometheus.requests()).isEmpty();
		}
	}

	@Test
	void queryRange_eachQuery_resolvesTheNameAgain() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			TableResolver resolver = TableResolver.answering(NAME, List.of("127.0.0.1"));
			try (HttpPrometheus client = new HttpPrometheus(server(prometheus.url(NAME), List.of(), false), tls(),
					resolver)) {
				client.queryRange("up", START, START + 60, 15);
				client.queryRange("up", START, START + 60, 15);
			}

			assertThat(resolver.asked()).containsExactly(NAME, NAME);
		}
	}

	@ParameterizedTest
	@ValueSource(ints = { 301, 302, 303, 307, 308 })
	void queryRange_redirect_isNotFollowed(int status) {
		try (StubPrometheus metadata = StubPrometheus.answering(200, StubPrometheus.SUCCESS);
				StubPrometheus prometheus = StubPrometheus.handling((exchange) -> {
					exchange.getResponseHeaders().set("Location", metadata.url() + "/latest/meta-data/");
					exchange.sendResponseHeaders(status, -1);
					exchange.close();
				})) {
			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(), List.of(), false), HostResolver.SYSTEM))
				.withMessage(prometheus.url() + " did not answer like a Prometheus server (HTTP " + status + ")");
			assertThat(metadata.requests()).isEmpty();
		}
	}

	@Test
	void queryRange_redirectWithASuccessBody_isRefusedWithItsStatus() {
		try (StubPrometheus metadata = StubPrometheus.answering(200, StubPrometheus.SUCCESS);
				StubPrometheus prometheus = StubPrometheus.handling((exchange) -> {
					exchange.getResponseHeaders().set("Location", metadata.url() + "/latest/meta-data/");
					StubPrometheus.send(exchange, 302, StubPrometheus.SUCCESS, true);
				})) {
			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(), List.of(), false), HostResolver.SYSTEM))
				.withMessage(prometheus.url() + " answered HTTP 302: up");
			assertThat(metadata.requests()).isEmpty();
		}
	}

	/**
	 * A path that starts with {@code //} names no host: the request carries the URL's
	 * host in its {@code Host} header and the path as written, as Python sends it, so a
	 * caller cannot pick another virtual host at the address checked.
	 */
	@Test
	void queryRange_pathThatLooksLikeAHost_isSentAsAPathToTheUrlsHost() {
		try (StubPrometheus prometheus = StubPrometheus.answering(200, StubPrometheus.SUCCESS)) {
			query(server(prometheus.url(NAME) + "//internal.svc:8080/x", List.of(NAME), false),
					TableResolver.answering(NAME, List.of("127.0.0.1")));

			assertThat(prometheus.requests()).singleElement().satisfies((request) -> {
				assertThat(request.host()).isEqualTo(NAME + ":" + prometheus.port());
				assertThat(request.uri()).startsWith("//internal.svc:8080/x/api/v1/query_range?query=up&");
			});
		}
	}

	@Test
	void queryRange_tlsWithAPathThatLooksLikeAHost_trustsTheUrlsHost() {
		try (StubPrometheus prometheus = StubPrometheus.tls(forName.server())) {
			query(server(prometheus.url(NAME) + "//other.test/x", List.of(), false), forName.trusting(),
					TableResolver.answering(NAME, List.of("127.0.0.1")));

			assertThat(prometheus.requests()).extracting(Request::host).containsExactly(NAME + ":" + prometheus.port());
		}
	}

	@Test
	void queryRange_tlsCertificateForThePathsHost_isRefused() {
		try (StubPrometheus prometheus = StubPrometheus.tls(forOtherName.server())) {
			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(NAME) + "//other.test/x", List.of(), false),
						forOtherName.trusting(), TableResolver.answering(NAME, List.of("127.0.0.1"))))
				.withMessage("cannot query " + prometheus.url(NAME)
						+ "//other.test/x: its TLS certificate is not valid for this host");
			assertThat(prometheus.requests()).isEmpty();
		}
	}

	@Test
	void queryRange_tlsCertificateForTheName_isTrustedAtTheAddressResolved() {
		try (StubPrometheus prometheus = StubPrometheus.tls(forName.server())) {
			query(server(prometheus.url(NAME), List.of(), false), forName.trusting(),
					TableResolver.answering(NAME, List.of("127.0.0.1")));

			assertThat(prometheus.requests()).extracting(Request::host).containsExactly(NAME + ":" + prometheus.port());
		}
	}

	@Test
	void queryRange_tlsCertificateForAnotherName_isRefused() {
		try (StubPrometheus prometheus = StubPrometheus.tls(forOtherName.server())) {
			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(NAME), List.of(), false), forOtherName.trusting(),
						TableResolver.answering(NAME, List.of("127.0.0.1"))))
				.withMessage(
						"cannot query " + prometheus.url(NAME) + ": its TLS certificate is not valid for this host");
			assertThat(prometheus.requests()).isEmpty();
		}
	}

	@Test
	void queryRange_tlsCertificateNobodyTrusted_isRefused() {
		try (StubPrometheus prometheus = StubPrometheus.tls(forName.server())) {
			assertThatExceptionOfType(PrometheusException.class)
				.isThrownBy(() -> query(server(prometheus.url(NAME), List.of(), false), forOtherName.trusting(),
						TableResolver.answering(NAME, List.of("127.0.0.1"))))
				.withMessage(
						"cannot query " + prometheus.url(NAME) + ": its TLS certificate is not valid for this host");
			assertThat(prometheus.requests()).isEmpty();
		}
	}

	/** Called by its address, a server must hold a certificate for that address. */
	@Test
	void queryRange_tlsAtAnAddress_isCheckedAgainstTheAddressNotAName() {
		try (StubPrometheus prometheus = StubPrometheus.tls(forName.server())) {
			assertThatExceptionOfType(PrometheusException.class).isThrownBy(
					() -> query(server(prometheus.url(), List.of(), false), forName.trusting(), HostResolver.SYSTEM))
				.withMessage("cannot query " + prometheus.url() + ": its TLS certificate is not valid for this host");
		}
	}

	private static void query(PrometheusServer server, HostResolver resolver) {
		query(server, tls(), resolver);
	}

	private static void query(PrometheusServer server, SSLContext tls, HostResolver resolver) {
		try (HttpPrometheus client = new HttpPrometheus(server, tls, resolver)) {
			client.queryRange("up", START, START + 60, 15);
		}
	}

	private static PrometheusServer server(String url, List<String> allowedHosts, boolean requireGlobal) {
		return new PrometheusServer(url, 5, MAX_BYTES, allowedHosts, requireGlobal);
	}

	private static SSLContext tls() {
		try {
			return SSLContext.getDefault();
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
