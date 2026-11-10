package io.archsim.engine.calibration.prometheus.http;

import java.net.InetAddress;
import java.net.UnknownHostException;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.ManagedHttpClientConnectionFactory;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.client5.http.ssl.HttpsSupport;
import org.apache.hc.core5.http.config.Http1Config;
import org.apache.hc.core5.http.impl.io.DefaultHttpResponseParserFactory;
import org.apache.hc.core5.util.Timeout;

/**
 * The HttpClient of a Prometheus client. Every request names the address it goes to, so
 * the client looks no name up: its resolver refuses every name. TLS is checked against
 * the request's host name, by the JDK and by HttpClient's own verifier. An answer is read
 * as strictly as Python's {@code http.client} reads it: a status line first, header lines
 * of at most 64 KiB and at most 100 headers, which the body's size cap does not count.
 * Nothing is redirected, retried, decompressed, sent through a proxy or sent again on a
 * connection already used, and no cookie or credential is kept.
 */
final class PinnedClients {

	/** The longest header line Python's {@code http.client} reads ({@code _MAXLINE}). */
	private static final int MAX_LINE = 65_536;

	/** The most headers Python's {@code http.client} reads ({@code _MAXHEADERS}). */
	private static final int MAX_HEADERS = 100;

	private PinnedClients() {
	}

	/**
	 * A client.
	 * @param timeout how long connecting, and each wait for bytes, may take
	 * @param tls the certificates trusted for HTTPS
	 * @return the client
	 */
	static CloseableHttpClient create(Timeout timeout, SSLContext tls) {
		Http1Config http1 = Http1Config.custom().setMaxLineLength(MAX_LINE).setMaxHeaderCount(MAX_HEADERS).build();
		return HttpClients.custom()
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				.setDnsResolver(NoLookups.INSTANCE)
				// a strict parser: HttpClient's own skips what comes before a status line
				.setConnectionFactory(ManagedHttpClientConnectionFactory.builder()
					.http1Config(http1)
					.responseParserFactory(new DefaultHttpResponseParserFactory(http1))
					.build())
				.setTlsSocketStrategy(new DefaultClientTlsStrategy(tls, HostnameVerificationPolicy.BOTH,
						HttpsSupport.getDefaultHostnameVerifier()))
				.setDefaultConnectionConfig(
						ConnectionConfig.custom().setConnectTimeout(timeout).setSocketTimeout(timeout).build())
				.build())
			.setConnectionReuseStrategy((request, response, context) -> false)
			.setDefaultRequestConfig(
					RequestConfig.custom().setRedirectsEnabled(false).setResponseTimeout(timeout).build())
			.disableRedirectHandling()
			.disableAutomaticRetries()
			.disableContentCompression()
			.disableCookieManagement()
			.disableAuthCaching()
			.disableConnectionState()
			.disableDefaultUserAgent()
			.build();
	}

	/**
	 * The resolver HttpClient is given: a request without its address is a mistake, and
	 * nothing is called.
	 */
	private enum NoLookups implements DnsResolver {

		/** The one resolver: it holds nothing. */
		INSTANCE;

		@Override
		public InetAddress[] resolve(String host) throws UnknownHostException {
			throw new UnknownHostException("the address was looked up already");
		}

		@Override
		public String resolveCanonicalHostname(String host) throws UnknownHostException {
			throw new UnknownHostException("the address was looked up already");
		}

	}

}
