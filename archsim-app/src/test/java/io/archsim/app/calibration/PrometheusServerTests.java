package io.archsim.app.calibration;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import io.archsim.app.config.CalibrationProperties.Outbound;
import io.archsim.app.web.ApiException;
import io.archsim.engine.calibration.prometheus.PrometheusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * Whether a calibration may call the Prometheus server it names, and how: the policy's
 * mode, hosts and limits, refused in the words the Python API answered.
 */
class PrometheusServerTests {

	private static final OutboundPolicy OPEN = new OutboundPolicy(Outbound.OPEN, List.of(), 30.0, 52_428_800L);

	private static final OutboundPolicy ALLOWLIST = new OutboundPolicy(Outbound.ALLOWLIST, List.of("prom:9090"), 5.0,
			2048);

	private static final OutboundPolicy OFF = new OutboundPolicy(Outbound.OFF, List.of(), 30.0, 52_428_800L);

	@Test
	void allowedBy_open_callsAnyAddressWithinThePolicysLimits() {
		assertThat(PrometheusServer.allowedBy(OPEN, "http://prom:9090"))
			.isEqualTo(new PrometheusServer("http://prom:9090", 30.0, 52_428_800L, List.of(), false));
	}

	@Test
	void allowedBy_allowlist_callsOnlyTheListedHostsAtPublicAddresses() {
		assertThat(PrometheusServer.allowedBy(ALLOWLIST, "http://prom:9090"))
			.isEqualTo(new PrometheusServer("http://prom:9090", 5.0, 2048, List.of("prom:9090"), true));
	}

	@ParameterizedTest
	@ValueSource(strings = { "http://prom:9090", "ftp://prom" })
	void allowedBy_off_isRefusedFirstSayingWhatToSendInstead(String url) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> PrometheusServer.allowedBy(OFF, url))
			.satisfies((ex) -> {
				assertThat(ex.status()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
				assertThat(ex.getMessage())
					.isEqualTo("This server does not call Prometheus servers (ARCHSIM_OUTBOUND): "
							+ "send recorded responses instead (archsim calibrate --record makes them)");
			});
	}

	@ParameterizedTest
	@ValueSource(strings = { "ftp://prom", "prom:9090", "http://", "file:///etc/passwd", "//prom:9090" })
	void allowedBy_urlThatIsNotHttp_isRefused(String url) {
		assertThatExceptionOfType(ApiException.class).isThrownBy(() -> PrometheusServer.allowedBy(OPEN, url))
			.withMessage("The Prometheus URL must be http(s)://host[:port]");
	}

	@Test
	void allowedBy_hostOffTheAllowlist_isRefused() {
		assertThatExceptionOfType(ApiException.class)
			.isThrownBy(() -> PrometheusServer.allowedBy(ALLOWLIST, "http://other:9090"))
			.withMessage("This Prometheus host is not allowed here (ARCHSIM_PROMETHEUS_ALLOWED_HOSTS)");
	}

	/**
	 * Python's {@code urlsplit} repeats a port that is not one
	 * ({@code Port could not be cast to integer value as 'SECRET'}) while it checks the
	 * allowlist; Java answers as the client does, without repeating it.
	 */
	@Test
	void allowedBy_portThatIsNotOneUnderAnAllowlist_isRefusedWithoutRepeatingIt() {
		assertThatExceptionOfType(PrometheusException.class)
			.isThrownBy(() -> PrometheusServer.allowedBy(ALLOWLIST, "http://prom:SECRET"))
			.withMessage("the Prometheus URL has an invalid port");
	}

}
