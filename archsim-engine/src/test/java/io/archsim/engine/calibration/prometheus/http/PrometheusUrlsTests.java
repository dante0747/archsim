package io.archsim.engine.calibration.prometheus.http;

import java.util.List;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.archsim.engine.calibration.prometheus.PrometheusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * What a policy asks of a Prometheus URL: whether it names an HTTP server, and whether an
 * allowlist allows it.
 */
class PrometheusUrlsTests {

	@ParameterizedTest
	@ValueSource(strings = { "http://prom:9090", "https://prom/path", "HTTP://prom" })
	void namesHttpServer_httpOrHttpsWithAHost_isTrue(String url) {
		boolean names = PrometheusUrls.namesHttpServer(url);

		assertThat(names).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://prom", "prom:9090", "http://", "file:///etc/passwd", "//prom:9090" })
	void namesHttpServer_otherSchemeOrNoHost_isFalse(String url) {
		boolean names = PrometheusUrls.namesHttpServer(url);

		assertThat(names).isFalse();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://[::1", "http://x[::1]:9090" })
	void namesHttpServer_bracketsThatHoldNoValidHost_isRefusedWithoutRepeatingTheUrl(String url) {
		assertThatExceptionOfType(PrometheusException.class).isThrownBy(() -> PrometheusUrls.namesHttpServer(url))
			.withMessage("Invalid IPv6 URL");
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://prom:9090", "http://PROM:9090/api" })
	void isAllowed_aListedHost_isTrue(String url) {
		boolean allowed = PrometheusUrls.isAllowed(url, List.of("prom:9090"));

		assertThat(allowed).isTrue();
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://other:9090", "http://prom:9091" })
	void isAllowed_aHostOffTheList_isFalse(String url) {
		boolean allowed = PrometheusUrls.isAllowed(url, List.of("prom:9090"));

		assertThat(allowed).isFalse();
	}

}
